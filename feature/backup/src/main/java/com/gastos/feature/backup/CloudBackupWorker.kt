package com.gastos.feature.backup

import android.content.Context
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
    @Assisted appContext: Context,
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
            preferences.recordError("Vuelve a conectar Google para reanudar el backup automático.")
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
                    featureLabel = "el backup automático",
                    networkMessage = "Sin conexión con Google Drive. FinAI volverá a intentarlo automáticamente.",
                    transientMessage = "Google Drive no responde temporalmente. FinAI volverá a intentarlo automáticamente.",
                    quotaMessage = "Google Drive no pudo guardar la copia por permisos o cuota. No se reintentará automáticamente.",
                    genericMessage = "No se pudo crear el backup automático. Revisa la configuración del backup."
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
