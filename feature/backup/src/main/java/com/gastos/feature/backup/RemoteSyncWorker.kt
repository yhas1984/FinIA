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
        // Keep the outbox work alive until Premium and the Google account are
        // available; success here would leave pending rows with no trigger.
        if (remoteSyncState.shouldDefer()) return Result.retry()
        val pending = outbox.pending()
        if (pending.isEmpty()) return Result.success()
        for (item in pending) {
            try {
                when (processor.process(item)) {
                    RemoteSyncOutcome.SUCCESS -> Unit
                    RemoteSyncOutcome.DEFERRED -> return Result.retry()
                    RemoteSyncOutcome.RETRY -> return Result.retry()
                }
            } catch (e: Exception) {
                SafeLog.e("RemoteSyncWorker", "sync failed ${item.targetKey}", e)
                return Result.retry()
            }
        }
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
        RemoteSyncTarget.INVOICE_DRIVE -> processDrive(item)
        RemoteSyncTarget.EXPENSE_SHEETS -> processExpense(item)
        RemoteSyncTarget.INCOME_SHEETS -> processIncome(item)
    }

    private suspend fun processDrive(item: RemoteSyncOutboxEntity): RemoteSyncOutcome {
        if (item.action != RemoteSyncAction.UPSERT) return RemoteSyncOutcome.SUCCESS
        val invoice = invoiceRepository.getInvoiceById(item.recordId) ?: return RemoteSyncOutcome.SUCCESS.also { outbox.delete(item.targetKey) }
        if (!invoice.driveUploadPending) return RemoteSyncOutcome.SUCCESS.also { outbox.delete(item.targetKey) }
        val result = invoiceDriveService.upload(invoice)
        return if (result.uploaded) {
            outbox.delete(item.targetKey); RemoteSyncOutcome.SUCCESS
        } else if (!result.invoice.driveUploadPending) {
            outbox.delete(item.targetKey); RemoteSyncOutcome.SUCCESS
        } else RemoteSyncOutcome.RETRY
    }

    private suspend fun processExpense(item: RemoteSyncOutboxEntity): RemoteSyncOutcome = when (item.action) {
        RemoteSyncAction.UPSERT -> if (sheetsSyncManager.performExpenseSync(item.recordId)) { outbox.delete(item.targetKey); RemoteSyncOutcome.SUCCESS } else RemoteSyncOutcome.DEFERRED
        RemoteSyncAction.DELETE -> if (sheetsSyncManager.performExpenseDelete(item.recordId)) { outbox.delete(item.targetKey); RemoteSyncOutcome.SUCCESS } else RemoteSyncOutcome.DEFERRED
    }

    private suspend fun processIncome(item: RemoteSyncOutboxEntity): RemoteSyncOutcome = when (item.action) {
        RemoteSyncAction.UPSERT -> {
            val income = incomeRepository.getIncomeById(item.recordId)
            if (income == null) { outbox.delete(item.targetKey); RemoteSyncOutcome.SUCCESS }
            else if (sheetsSyncManager.performIncomeUpsert(item.recordId)) { outbox.delete(item.targetKey); RemoteSyncOutcome.SUCCESS }
            else RemoteSyncOutcome.DEFERRED
        }
        RemoteSyncAction.DELETE -> if (sheetsSyncManager.performIncomeDelete(item.recordId)) { outbox.delete(item.targetKey); RemoteSyncOutcome.SUCCESS } else RemoteSyncOutcome.DEFERRED
    }
}
