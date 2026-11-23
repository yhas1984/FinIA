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

/**
 * Petición de `POST /v1/installs:register`.
 *
 * Lista cerrada a propósito: el JSON se deserializa con `ignoreUnknownKeys = false`, así que un
 * campo que no esté aquí hace fallar la petición con 400 en vez de guardarse sin que nadie lo vea.
 * Es la barrera que impide que un día se cuele un importe, un NIF o un identificador de publicidad
 * por «añadir un campo rápido».
 */
@Serializable
data class RegisterInstallRequest(
    val installId: String,
    val stage: String = InstallRegistry.STAGE_INSTALL,
    val utmSource: String? = null,
    val utmMedium: String? = null,
    val utmCampaign: String? = null,
    val utmContent: String? = null,
    val utmTerm: String? = null,
    val appVersionName: String? = null,
    val appVersionCode: Int? = null,
    val locale: String? = null,
    val country: String? = null,
    val firstRunAt: Long? = null,
    val referrerClickTs: Long? = null,
    val installBeginTs: Long? = null
)

@Serializable
data class RegisterInstallResponse(
    val status: String,
    val stage: String,
    val duplicate: Boolean
)

data class InstallRecord(
    val installId: String,
    val stage: String,
    val utmSource: String?,
    val utmMedium: String?,
    val utmCampaign: String?,
    val utmContent: String?,
    val utmTerm: String?,
    val appVersionName: String?,
    val appVersionCode: Int?,
    val locale: String?,
    val country: String?,
    val firstRunAt: Long?,
    val referrerClickTs: Long?,
    val installBeginTs: Long?,
    val seenAt: Long
)

@Serializable
data class HealthResponse(val status: String)

@Serializable
data class ErrorResponse(val error: String, val code: String? = null)

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

/**
 * Fallo del servicio de Google Play (o de su autenticación) al verificar una compra.
 *
 * Existe para que el cliente pueda distinguir «este token no vale» de «el backend está caído»:
 * antes, cualquier respuesta no-2xx de Google se convertía en un 503 genérico y un usuario con una
 * compra legítima veía exactamente el mismo error que si el servidor estuviera roto, sin rastro del
 * motivo ni en el cliente ni en las métricas.
 *
 * Ojo con el 404: Play devuelve 404 tanto si el token no existe como si el paquete o el producto
 * de la configuración no existen. Desde aquí son indistinguibles; el log dice cuál llegó y la
 * revisión de la configuración de Play es cosa de quien lea ese log.
 */
class UpstreamPlayException(val upstreamStatus: Int, message: String) : RuntimeException(message)
