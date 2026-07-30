package com.gastos

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.gastos.feature.backup.BackupArchiveService
import com.gastos.feature.backup.CloudBackupScheduler
import com.gastos.repository.CountryFiscalConfigRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class GastosApp : Application(), Configuration.Provider {
    @Inject lateinit var fiscalConfigRepository: CountryFiscalConfigRepository
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var cloudBackupScheduler: CloudBackupScheduler
    @Inject lateinit var backupArchiveService: BackupArchiveService

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val startupTime = System.currentTimeMillis()
        applicationScope.launch {
            runCatching { fiscalConfigRepository.insertDefaultConfigs() }
            runCatching { backupArchiveService.cleanupTemporaryFiles(startupTime) }
        }
        cloudBackupScheduler.reconcile()
    }
}
