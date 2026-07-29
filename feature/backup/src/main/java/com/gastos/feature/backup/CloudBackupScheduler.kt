package com.gastos.feature.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudBackupScheduler @Inject constructor(
    @ApplicationContext context: Context,
    private val preferences: CloudBackupPreferences
) {
    private val workManager = WorkManager.getInstance(context)

    fun reconcile() {
        if (preferences.status().enabled) schedulePeriodic() else cancel()
    }

    fun setEnabled(enabled: Boolean) {
        preferences.setEnabled(enabled)
        if (enabled) {
            schedulePeriodic()
            enqueueNow()
        } else {
            cancel()
        }
    }

    fun enqueueNow() {
        val request = OneTimeWorkRequestBuilder<CloudBackupWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel() {
        workManager.cancelUniqueWork(PERIODIC_WORK)
        workManager.cancelUniqueWork(IMMEDIATE_WORK)
    }

    private fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<CloudBackupWorker>(24, TimeUnit.HOURS)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun networkConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    private companion object {
        const val PERIODIC_WORK = "finai_encrypted_cloud_backup"
        const val IMMEDIATE_WORK = "finai_encrypted_cloud_backup_now"
    }
}
