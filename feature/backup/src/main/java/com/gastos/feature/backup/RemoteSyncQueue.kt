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

interface RemoteSyncScheduler {
    fun schedule()
    fun scheduleAfter(delayMillis: Long) = schedule()
}

@Singleton
open class RemoteSyncQueue @Inject constructor(@ApplicationContext context: Context) : RemoteSyncScheduler {
    private val workManager = WorkManager.getInstance(context)

    override fun schedule() = enqueue(0L, WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE)

    override fun scheduleAfter(delayMillis: Long) = enqueue(delayMillis, "$WORK_NAME.timer", ExistingWorkPolicy.REPLACE)

    private fun enqueue(delayMillis: Long, name: String, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<RemoteSyncWorker>()
            .setConstraints(networkConstraints())
            .setInitialDelay(delayMillis.coerceAtLeast(0), java.util.concurrent.TimeUnit.MILLISECONDS)
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(name, policy, request)
    }

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    companion object { const val WORK_NAME = "finai_remote_sync" }
}
