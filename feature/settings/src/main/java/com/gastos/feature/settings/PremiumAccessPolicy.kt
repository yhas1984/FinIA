package com.gastos.feature.settings

sealed interface EntitlementVerification {
    data class Valid(val token: String, val expiresAt: Long, val purchaseHash: String) : EntitlementVerification
    data object Revoked : EntitlementVerification
    data object Unavailable : EntitlementVerification
    data object Invalid : EntitlementVerification
}

internal object PremiumAccessPolicy {
    const val MAX_GRACE_MILLIS = 7L * 24 * 60 * 60 * 1000

    fun isValid(verifiedAt: Long, grantExpiresAt: Long?, now: Long): Boolean =
        verifiedAt > 0 && verifiedAt <= now && now - verifiedAt < MAX_GRACE_MILLIS &&
            (grantExpiresAt == null || now < grantExpiresAt)
}
