package com.gastos.billing

import kotlinx.serialization.Serializable

@Serializable
data class VerifyEntitlementRequest(
    val packageName: String,
    val productId: String,
    val purchaseToken: String,
    val playIntegrityToken: String? = null
)

@Serializable
data class EntitlementResponse(
    val entitlementToken: String,
    val status: String,
    val productId: String,
    val expiresAt: Long
)

@Serializable
data class ReconcileResponse(
    val revoked: Int
)

@Serializable
data class HealthResponse(val status: String)

@Serializable
data class ErrorResponse(val error: String)

data class VerifiedPurchase(
    val orderId: String?,
    val purchaseTimeMillis: Long?,
    val acknowledgementState: Int
)

data class EntitlementRecord(
    val tokenHash: String,
    val packageName: String,
    val productId: String,
    val orderId: String?,
    val status: String,
    val updatedAt: Long
)

data class BillingConfig(
    val projectId: String,
    val firestoreDatabaseId: String,
    val packageName: String,
    val productId: String,
    val entitlementIssuer: String,
    val entitlementKeyId: String,
    val entitlementPrivateKeyPem: String,
    val internalReconcileSecret: String,
    val requirePlayIntegrity: Boolean,
    val reconcileAudience: String = ""
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): BillingConfig = BillingConfig(
            projectId = env.required("GCP_PROJECT_ID"),
            firestoreDatabaseId = env.getOrDefault("FIRESTORE_DATABASE_ID", "finai"),
            packageName = env.getOrDefault("PLAY_PACKAGE_NAME", "com.gastos.ingresos"),
            productId = env.getOrDefault("PLAY_PRODUCT_ID", "finai_premium"),
            entitlementIssuer = env.getOrDefault("ENTITLEMENT_ISSUER", "finai-billing"),
            entitlementKeyId = env.required("ENTITLEMENT_KEY_ID"),
            entitlementPrivateKeyPem = env.required("ENTITLEMENT_PRIVATE_KEY_PEM").replace("\\n", "\n"),
            internalReconcileSecret = env.required("INTERNAL_RECONCILE_SECRET"),
            requirePlayIntegrity = env.getOrDefault("REQUIRE_PLAY_INTEGRITY", "false").toBoolean(),
            reconcileAudience = env.required("RECONCILE_AUDIENCE")
        )

        private fun Map<String, String>.required(name: String): String =
            get(name)?.takeIf(String::isNotBlank) ?: error("Missing required environment variable: $name")
    }
}

class ClientInputException(message: String) : RuntimeException(message)
class PurchaseNotEntitledException : RuntimeException("Purchase is not entitled")
class ExternalServiceException(message: String) : RuntimeException(message)
