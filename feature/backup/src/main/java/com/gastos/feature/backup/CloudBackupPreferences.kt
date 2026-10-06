package com.gastos.feature.backup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class CloudBackupStatus(
    val enabled: Boolean,
    val lastSuccessAt: Long?,
    val lastError: String?,
    val accountKey: String? = null,
    val needsConsentConfirmation: Boolean = false
)

@Singleton
class CloudBackupPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var activeAccount: String? = prefs.getString("active_account", null)
    init {
        if (!prefs.getBoolean("account_scope_migrated", false)) {
            val legacyEnabled = prefs.getBoolean(KEY_ENABLED, false)
            val knownAccount = context.getSharedPreferences(SheetsLinkStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString("last_account_key", null)?.takeIf(String::isNotBlank)
            val migration = prefs.edit().putBoolean("account_scope_migrated", true)
                .remove(KEY_ENABLED).remove(KEY_LAST_SUCCESS).remove(KEY_LAST_ERROR)
                .putBoolean("legacy_consent_pending", legacyEnabled && knownAccount == null)
            if (legacyEnabled && knownAccount != null) migration.putBoolean(key(KEY_ENABLED, knownAccount), true)
            migration.apply()
        }
    }
    private val mutableStatus: MutableStateFlow<CloudBackupStatus> = MutableStateFlow(readStatus())

    val statusFlow: StateFlow<CloudBackupStatus> = mutableStatus.asStateFlow()

    fun status(): CloudBackupStatus = mutableStatus.value

    private fun key(name: String, account: String? = activeAccount): String = "$name:${account.orEmpty()}"

    fun selectAccount(account: String?) {
        activeAccount = account?.takeIf(String::isNotBlank)
        prefs.edit().putString("active_account", activeAccount).apply()
        mutableStatus.value = readStatus()
    }

    private fun readStatus(): CloudBackupStatus = CloudBackupStatus(
        enabled = activeAccount != null && prefs.getBoolean(key(KEY_ENABLED), false),
        lastSuccessAt = activeAccount?.let { prefs.getLong(key(KEY_LAST_SUCCESS), 0L).takeIf { it > 0L } },
        lastError = activeAccount?.let { prefs.getString(key(KEY_LAST_ERROR), null) },
        accountKey = activeAccount,
        needsConsentConfirmation = activeAccount != null && prefs.getBoolean("legacy_consent_pending", false)
    )

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(key(KEY_ENABLED), enabled).remove("legacy_consent_pending").apply()
        mutableStatus.value = readStatus()
    }

    fun recordSuccess(timestamp: Long, account: String? = activeAccount) {
        prefs.edit()
            .putLong(key(KEY_LAST_SUCCESS, account), timestamp)
            .remove(key(KEY_LAST_ERROR, account))
            .apply()
        mutableStatus.value = readStatus()
    }

    fun recordError(message: String, account: String? = activeAccount) {
        prefs.edit().putString(key(KEY_LAST_ERROR, account), message.take(240)).apply()
        mutableStatus.value = readStatus()
    }

    private companion object {
        const val PREFS_NAME = "finai_cloud_backup"
        const val KEY_ENABLED = "enabled"
        const val KEY_LAST_SUCCESS = "last_success"
        const val KEY_LAST_ERROR = "last_error"
    }
}
