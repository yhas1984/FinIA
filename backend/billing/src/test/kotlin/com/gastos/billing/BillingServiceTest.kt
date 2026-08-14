package com.gastos.billing

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BillingServiceTest {
    @Test
    fun `revoked purchase cannot be reactivated by a later verify request`() = runTest {
        val playApi = RecordingPlayApi()
        val service = BillingService(
            config = testConfig(),
            playApi = playApi,
            store = RevokedStore(),
            signer = object : EntitlementTokenSigner {
                override fun issue(record: EntitlementRecord): EntitlementResponse =
                    error("signer must not be called")
            }
        )

        assertFailsWith<PurchaseNotEntitledException> {
            service.verify(VerifyEntitlementRequest("com.gastos.ingresos", "finai_premium", "purchase-token"))
        }
        assertEquals(0, playApi.verifyCalls)
    }

    private class RevokedStore : EntitlementStore {
        override suspend fun save(record: EntitlementRecord): Boolean = error("save must not be called")
        override suspend fun status(tokenHash: String): String = "revoked"
        override suspend fun revoke(tokenHash: String) = Unit
    }

    private class RecordingPlayApi : PlayPurchaseGateway {
        var verifyCalls = 0

        override suspend fun verifyPurchase(productId: String, purchaseToken: String): VerifiedPurchase {
            verifyCalls += 1
            return VerifiedPurchase("order", 1L, 1)
        }

        override suspend fun acknowledge(productId: String, purchaseToken: String) = Unit
        override suspend fun listVoidedPurchaseTokens(): List<String> = emptyList()
        override suspend fun verifyPlayIntegrity(token: String, expectedNonce: String) = Unit
    }

    private fun testConfig() = BillingConfig(
        projectId = "finai-501616",
        firestoreDatabaseId = "finai",
        packageName = "com.gastos.ingresos",
        productId = "finai_premium",
        entitlementIssuer = "test",
        entitlementKeyId = "test-key",
        entitlementPrivateKeyPem = "unused",
        internalReconcileSecret = "secret",
        requirePlayIntegrity = false,
        reconcileAudience = "https://billing.test"
    )
}
