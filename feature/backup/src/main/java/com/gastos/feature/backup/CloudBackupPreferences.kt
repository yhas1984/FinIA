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
    val lastError: String?
)

@Singleton
class CloudBackupPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutableStatus: MutableStateFlow<CloudBackupStatus> = MutableStateFlow(readStatus())

    val statusFlow: StateFlow<CloudBackupStatus> = mutableStatus.asStateFlow()

    fun status(): CloudBackupStatus = mutableStatus.value

    private fun readStatus(): CloudBackupStatus = CloudBackupStatus(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        lastSuccessAt = prefs.getLong(KEY_LAST_SUCCESS, 0L).takeIf { it > 0L },
        lastError = prefs.getString(KEY_LAST_ERROR, null)
    )

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        mutableStatus.value = readStatus()
    }

    fun recordSuccess(timestamp: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong(KEY_LAST_SUCCESS, timestamp)
            .remove(KEY_LAST_ERROR)
            .apply()
        mutableStatus.value = readStatus()
    }

    fun recordError(message: String) {
        prefs.edit().putString(KEY_LAST_ERROR, message.take(240)).apply()
        mutableStatus.value = readStatus()
    }

    private companion object {
        const val PREFS_NAME = "finai_cloud_backup"
        const val KEY_ENABLED = "enabled"
        const val KEY_LAST_SUCCESS = "last_success"
        const val KEY_LAST_ERROR = "last_error"
    }
}
