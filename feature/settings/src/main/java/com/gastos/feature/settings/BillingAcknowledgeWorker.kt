package com.gastos.feature.settings

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class BillingAcknowledgeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val billingManager: BillingManager
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        billingManager.processPendingAcknowledge()
        return Result.success()
    }
}
