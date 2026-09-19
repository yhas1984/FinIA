package com.gastos.feature.backup

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gastos.extension.SafeLog
import com.gastos.repository.IncomeRepository
import com.gastos.repository.InvoiceRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class RemoteSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val outbox: RemoteSyncOutboxRepository,
    private val invoiceRepository: InvoiceRepository,
    private val incomeRepository: IncomeRepository,
    private val invoiceDriveService: InvoiceDriveService,
    private val sheetsSyncManager: SheetsSyncManager,
    private val remoteSyncState: RemoteSyncState
) : CoroutineWorker(appContext, params) {
    private val processor = RemoteSyncProcessor(outbox, invoiceRepository, incomeRepository, invoiceDriveService, sheetsSyncManager)

    override suspend fun doWork(): Result {
        if (remoteSyncState.shouldDefer()) return Result.retry()
        val now = System.currentTimeMillis()
        val pending = outbox.pending().filter { (it.status == RemoteSyncStatus.PENDING && it.nextAttemptAt <= now) || it.status == RemoteSyncStatus.WAITING_AUTH }
        for (item in pending) {
            try {
                when (processor.process(item)) {
                    RemoteSyncOutcome.SUCCESS -> Unit
                    RemoteSyncOutcome.DEFERRED -> outbox.failed(item, "AUTH_OR_LINK_REQUIRED", consumeAttempt = false)
                    RemoteSyncOutcome.RETRY -> outbox.failed(item, "REMOTE_UNAVAILABLE")
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val category = GoogleApiErrorClassifier.classify(error,
                    GoogleApiErrorContext("sync", "OFFLINE", "SERVER_UNAVAILABLE", "PERMISSION_OR_QUOTA", "SYNC_FAILED"))
                val deferred = category.category in setOf(GoogleApiErrorCategory.NETWORK, GoogleApiErrorCategory.AUTH_RECOVERABLE,
                    GoogleApiErrorCategory.AUTH_PERMANENT, GoogleApiErrorCategory.PLAY_SERVICES)
                outbox.failed(item, category.category.name, consumeAttempt = !deferred,
                    permanent = !deferred && !category.shouldRetry)
                SafeLog.w("RemoteSyncWorker", "${item.target}: ${category.category}")
            }
        }
        outbox.scheduleNext()
        return Result.success()
    }
}

enum class RemoteSyncOutcome { SUCCESS, DEFERRED, RETRY }

interface RemoteSyncState { fun shouldDefer(): Boolean }

internal class RemoteSyncProcessor(
    private val outbox: RemoteSyncOutboxRepository,
    private val invoiceRepository: InvoiceRepository,
    private val incomeRepository: IncomeRepository,
    private val invoiceDriveService: InvoiceDriveService,
    private val sheetsSyncManager: SheetsSyncManager
) {
    suspend fun process(item: RemoteSyncOutboxEntity): RemoteSyncOutcome = when (item.target) {
        RemoteSyncTarget.INVOICE_DRIVE, RemoteSyncTarget.INCOME_DRIVE -> processDrive(item)
        RemoteSyncTarget.EXPENSE_SHEETS -> processExpense(item)
        RemoteSyncTarget.INCOME_SHEETS -> processIncome(item)
    }

    private suspend fun processDrive(item: RemoteSyncOutboxEntity): RemoteSyncOutcome {
        if (item.action == RemoteSyncAction.DELETE) {
            if (!item.deleteConsent || item.accountId.isNullOrBlank() || item.remoteFileId.isNullOrBlank()) {
                outbox.delete(item)
                return RemoteSyncOutcome.SUCCESS
            }
            val deleted = outbox.withCurrent(item) { invoiceDriveService.delete(item.remoteFileId, item.accountId) }
                ?: return RemoteSyncOutcome.SUCCESS
            return if (deleted) { outbox.delete(item); RemoteSyncOutcome.SUCCESS } else RemoteSyncOutcome.DEFERRED
        }
        if (item.target == RemoteSyncTarget.INCOME_DRIVE) {
            val income = incomeRepository.getIncomeById(item.recordId)
            if (income == null || item.documentUuid.isNotBlank() && item.documentUuid != income.documentUuid) {
                outbox.delete(item); return RemoteSyncOutcome.SUCCESS
            }
            val result = invoiceDriveService.upload(income) { outbox.isCurrent(item) }
            if (result.uploaded) {
                outbox.delete(item)
                sheetsSyncManager.upsertIncome(result.income)
            } else outbox.failed(item, result.message, !result.deferred, result.permanent)
        } else {
            val invoice = invoiceRepository.getInvoiceById(item.recordId)
            if (invoice == null || item.documentUuid.isNotBlank() && item.documentUuid != invoice.documentUuid) {
                outbox.delete(item); return RemoteSyncOutcome.SUCCESS
            }
            val result = invoiceDriveService.upload(invoice) { outbox.isCurrent(item) }
            if (result.uploaded) {
                outbox.delete(item)
                sheetsSyncManager.upsertExpense(result.invoice)
            } else outbox.failed(item, result.message, !result.deferred, result.permanent)
        }
        return RemoteSyncOutcome.SUCCESS
    }

    private suspend fun processExpense(item: RemoteSyncOutboxEntity): RemoteSyncOutcome = when (item.action) {
        RemoteSyncAction.UPSERT -> {
            val synced = outbox.withCurrent(item) { sheetsSyncManager.performExpenseSync(item.recordId) }
                ?: return RemoteSyncOutcome.SUCCESS
            if (synced) { outbox.delete(item); RemoteSyncOutcome.SUCCESS } else RemoteSyncOutcome.DEFERRED
        }
        RemoteSyncAction.DELETE -> {
            val deleted = outbox.withCurrent(item) { sheetsSyncManager.performExpenseDelete(item.recordId) }
                ?: return RemoteSyncOutcome.SUCCESS
            if (deleted) { outbox.delete(item); RemoteSyncOutcome.SUCCESS } else RemoteSyncOutcome.DEFERRED
        }
    }

    private suspend fun processIncome(item: RemoteSyncOutboxEntity): RemoteSyncOutcome = when (item.action) {
        RemoteSyncAction.UPSERT -> {
            val income = incomeRepository.getIncomeById(item.recordId)
            if (income == null) { outbox.delete(item); RemoteSyncOutcome.SUCCESS }
            else {
                val synced = outbox.withCurrent(item) { sheetsSyncManager.performIncomeUpsert(item.recordId) }
                    ?: return RemoteSyncOutcome.SUCCESS
                if (synced) { outbox.delete(item); RemoteSyncOutcome.SUCCESS } else RemoteSyncOutcome.DEFERRED
            }
        }
        RemoteSyncAction.DELETE -> {
            val deleted = outbox.withCurrent(item) { sheetsSyncManager.performIncomeDelete(item.recordId) }
                ?: return RemoteSyncOutcome.SUCCESS
            if (deleted) { outbox.delete(item); RemoteSyncOutcome.SUCCESS } else RemoteSyncOutcome.DEFERRED
        }
    }
}
