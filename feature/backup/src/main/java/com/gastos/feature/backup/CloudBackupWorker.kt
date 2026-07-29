package com.gastos.feature.backup

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gastos.extension.SafeLog
import com.gastos.repository.PremiumStatusProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException

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
        } catch (error: IOException) {
            SafeLog.w(TAG, "Backup automático aplazado por error de red", error)
            preferences.recordError("Sin conexión. FinAI volverá a intentarlo automáticamente.")
            Result.retry()
        } catch (error: Exception) {
            SafeLog.e(TAG, "Backup automático falló", error)
            preferences.recordError(error.message ?: "No se pudo crear el backup automático.")
            Result.success()
        }
    }

    private companion object {
        const val TAG = "CloudBackupWorker"
    }
}
