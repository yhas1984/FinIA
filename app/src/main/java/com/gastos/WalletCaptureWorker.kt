package com.gastos

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gastos.domain.model.AutomationCodec
import com.gastos.domain.model.WalletPayment
import com.gastos.storage.WalletPaymentStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import java.util.concurrent.TimeUnit

/** WorkManager durably keeps only normalized data, before any expense is inserted. */
@HiltWorker
class WalletCaptureWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val payments: WalletPaymentStore
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        return try {
            inputData.getString(PAYMENT)?.let { payload ->
                val payment: WalletPayment = AutomationCodec.json.decodeFromString(payload)
                payments.receive(payment, inputData.getString(SESSION).orEmpty())
            }
            val hasAccess = { NotificationManagerCompat.getEnabledListenerPackages(applicationContext).contains(applicationContext.packageName) }
            if (payments.retryPending(hasAccess)) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A database/storage failure must not silently discard the captured event.
            Result.retry()
        }
    }

    companion object {
        private const val PAYMENT: String = "payment"
        private const val SESSION: String = "session"
        private const val TAG: String = "finai-wallet-capture"
        fun schedule(context: Context, payment: WalletPayment? = null, session: String = "", replaceExisting: Boolean = false) {
            val builder = OneTimeWorkRequestBuilder<WalletCaptureWorker>().addTag(TAG)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            if (payment != null) builder.setInputData(workDataOf(PAYMENT to AutomationCodec.json.encodeToString(payment), SESSION to session))
            WorkManager.getInstance(context).enqueueUniqueWork("$TAG:${payment?.eventId ?: "recovery"}",
                if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, builder.build())
        }
    }
}
