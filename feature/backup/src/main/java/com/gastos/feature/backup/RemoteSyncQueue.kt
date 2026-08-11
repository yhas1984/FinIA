package com.gastos.feature.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface RemoteSyncScheduler { fun schedule() }

@Singleton
open class RemoteSyncQueue @Inject constructor(@ApplicationContext context: Context) : RemoteSyncScheduler {
    private val workManager = WorkManager.getInstance(context)

    override fun schedule() {
        val request = OneTimeWorkRequestBuilder<RemoteSyncWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    companion object { const val WORK_NAME = "finai_remote_sync" }
}
