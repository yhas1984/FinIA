package com.gastos.billing

class BillingService(
    private val config: BillingConfig,
    private val playApi: PlayPurchaseGateway,
    private val store: EntitlementStore,
    private val signer: EntitlementTokenSigner
) {
    suspend fun verify(request: VerifyEntitlementRequest): EntitlementResponse {
        if (request.packageName != config.packageName) throw ClientInputException("Unsupported package")
        if (request.productId != config.productId) throw ClientInputException("Unsupported product")
        if (request.purchaseToken.isBlank()) throw ClientInputException("Purchase token is required")
        val tokenHash = hashPurchaseToken(request.purchaseToken)
        if (store.status(tokenHash) == "revoked") throw PurchaseNotEntitledException()
        if (config.requirePlayIntegrity && request.playIntegrityToken.isNullOrBlank()) {
            throw ClientInputException("Play Integrity token is required")
        }
        if (config.requirePlayIntegrity) {
            playApi.verifyPlayIntegrity(requireNotNull(request.playIntegrityToken), tokenHash)
        }

        val purchase = playApi.verifyPurchase(request.productId, request.purchaseToken)
        if (purchase.acknowledgementState != ACKNOWLEDGED_STATE) {
            playApi.acknowledge(request.productId, request.purchaseToken)
        }
        val record = EntitlementRecord(
            tokenHash = tokenHash,
            packageName = request.packageName,
            productId = request.productId,
            orderId = purchase.orderId,
            status = "active",
            updatedAt = System.currentTimeMillis()
        )
        if (!store.save(record)) throw PurchaseNotEntitledException()
        return signer.issue(record)
    }

    suspend fun reconcileVoidedPurchases(): Int {
        val tokens = playApi.listVoidedPurchaseTokens()
        tokens.forEach { store.revoke(hashPurchaseToken(it)) }
        return tokens.size
    }

    private companion object {
        const val ACKNOWLEDGED_STATE = 1
    }
}
