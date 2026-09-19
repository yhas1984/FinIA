package com.gastos

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.gastos.feature.backup.BackupArchiveService
import com.gastos.feature.backup.CloudBackupScheduler
import com.gastos.feature.backup.RemoteSyncQueue
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
    @Inject lateinit var remoteSyncOutbox: com.gastos.feature.backup.RemoteSyncOutboxRepository
    @Inject lateinit var backupDataRepository: com.gastos.repository.BackupDataRepository
    @Inject lateinit var remoteSyncQueue: RemoteSyncQueue

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
            runCatching { backupArchiveService.recoverInterruptedRestore() }
            runCatching { backupArchiveService.cleanupTemporaryFiles(startupTime) }
            runCatching { remoteSyncOutbox.reconcile(backupDataRepository.snapshot(), preserveDeletes = true) }
        }
        cloudBackupScheduler.reconcile()
        remoteSyncQueue.schedule()
    }
}
