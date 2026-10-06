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
import javax.inject.Singleton

@HiltAndroidApp
class GastosApp : Application(), Configuration.Provider {
    // The isolated PDF process must not construct repositories or access private preferences.
    @Inject lateinit var startup: dagger.Lazy<AppStartup>
    @Inject lateinit var workerFactory: dagger.Lazy<HiltWorkerFactory>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory.get()).build()

    override fun onCreate() {
        super.onCreate()
        val isolated = if (android.os.Build.VERSION.SDK_INT >= 28) android.os.Process.isIsolated()
            else !android.os.Process.isApplicationUid(android.os.Process.myUid())
        if (!isolated) startup.get().start()
    }
}

@Singleton
class AppStartup @Inject constructor() {
    @Inject @dagger.hilt.android.qualifiers.ApplicationContext lateinit var context: android.content.Context
    @Inject lateinit var fiscalConfigRepository: CountryFiscalConfigRepository
    @Inject lateinit var cloudBackupScheduler: CloudBackupScheduler
    @Inject lateinit var backupArchiveService: BackupArchiveService
    @Inject lateinit var remoteSyncOutbox: com.gastos.feature.backup.RemoteSyncOutboxRepository
    @Inject lateinit var backupDataRepository: com.gastos.repository.BackupDataRepository
    @Inject lateinit var remoteSyncQueue: RemoteSyncQueue

    @Inject lateinit var categoryCatalog: com.gastos.storage.CategoryCatalog
    @Inject lateinit var syncRelay: com.gastos.feature.backup.AutomationSyncRelay

    @Inject lateinit var limitNotifier: com.gastos.automation.LimitNotifier

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        val startupTime = System.currentTimeMillis()
        applicationScope.launch {
            runCatching { fiscalConfigRepository.insertDefaultConfigs() }
            runCatching { categoryCatalog.initialize() }
            runCatching { backupArchiveService.recoverInterruptedRestore() }
            runCatching { backupArchiveService.cleanupTemporaryFiles(startupTime) }
            runCatching { remoteSyncOutbox.reconcile(backupDataRepository.snapshot(), preserveDeletes = true) }
            WalletCaptureWorker.schedule(context)
        }
        applicationScope.launch { syncRelay.observe() }
        applicationScope.launch { limitNotifier.observe() }
        cloudBackupScheduler.reconcile()
        remoteSyncQueue.schedule()
    }
}
