package com.gastos.feature.backup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gastos.extension.SafeLog
import com.gastos.repository.PremiumStatusProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

@HiltWorker
class CloudBackupWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val cloudBackupService: CloudBackupService,
    private val archiveService: BackupArchiveService,
    private val sheetsExportService: SheetsExportService,
    private val premiumStatus: PremiumStatusProvider,
    private val preferences: CloudBackupPreferences
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!preferences.status().enabled) return Result.success()
        if (!premiumStatus.isPremium.value) return Result.success()
        if (!archiveService.isPasswordConfigured()) return Result.success()
        if (!sheetsExportService.isSignedIn()) {
            preferences.recordError(appContext.getString(R.string.reconnect_google_backup_auto))
            return Result.success()
        }
        return try {
            cloudBackupService.createBackup()
            preferences.recordSuccess()
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val classified = GoogleApiErrorClassifier.classify(
                error,
                GoogleApiErrorContext(
                    featureLabel = appContext.getString(R.string.auto_backup_feature_label),
                    networkMessage = appContext.getString(R.string.drive_network_message),
                    transientMessage = appContext.getString(R.string.drive_transient_message),
                    quotaMessage = appContext.getString(R.string.drive_quota_message),
                    genericMessage = appContext.getString(R.string.drive_generic_message)
                )
            )
            if (classified.category != GoogleApiErrorCategory.CANCELLATION) {
                SafeLog.e(TAG, "Backup automático falló (${classified.category})", error)
            }
            preferences.recordError(classified.message)
            if (classified.shouldRetry && runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.success()
        }
    }

    private companion object {
        const val TAG = "CloudBackupWorker"
        const val MAX_RETRY_ATTEMPTS = 3
    }
}
