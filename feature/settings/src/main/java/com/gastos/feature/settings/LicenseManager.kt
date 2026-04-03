package com.gastos.feature.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LicenseManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "finai_license"
        private const val KEY_LICENSE_CODE = "license_code"
        private const val KEY_IS_PRO = "is_pro"

        // These are example valid codes - in production you'd generate these server-side
        private val VALID_LICENSE_CODES = setOf(
            "FINAI-PRO-2026-XXXX",
            "FINAI-2026-XXXX",
            "FINAI-XXXX-2026",
            "FINAI-PRO-FREE"
        )
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isPro(): Boolean = prefs.getBoolean(KEY_IS_PRO, false)

    fun getLicenseCode(): String? = prefs.getString(KEY_LICENSE_CODE, null)

    fun activateLicense(code: String): LicenseResult {
        val normalizedCode = code.trim().uppercase()

        if (normalizedCode in VALID_LICENSE_CODES || isValidChecksum(normalizedCode)) {
            prefs.edit()
                .putString(KEY_LICENSE_CODE, normalizedCode)
                .putBoolean(KEY_IS_PRO, true)
                .apply()
            return LicenseResult.Success
        }

        return LicenseResult.InvalidCode
    }

    fun deactivateLicense() {
        prefs.edit()
            .remove(KEY_LICENSE_CODE)
            .putBoolean(KEY_IS_PRO, false)
            .apply()
    }

    private fun isValidChecksum(code: String): Boolean {
        return try {
            if (!code.startsWith("FINAI-")) return false
            val parts = code.split("-")
            if (parts.size < 3) return false

            val hash = MessageDigest.getInstance("MD5").digest(code.toByteArray())
                .joinToString("") { "%02x".format(it) }

            hash.startsWith(parts.last().lowercase().take(8))
        } catch (e: Exception) {
            false
        }
    }
}

sealed class LicenseResult {
    object Success : LicenseResult()
    object InvalidCode : LicenseResult()
}
