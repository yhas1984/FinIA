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
        // Drain again after each snapshot so writes that arrive during a
        // running worker are processed even when WorkManager coalesces work.
        while (true) {
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
        }
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
        if (item.action == RemoteSyncAction.DELETE) {
            val remoteFileId = item.remoteFileId ?: return RemoteSyncOutcome.SUCCESS.also { outbox.delete(item) }
            val deleted = outbox.withCurrent(item) { invoiceDriveService.delete(remoteFileId) }
                ?: return RemoteSyncOutcome.SUCCESS
            return if (deleted) {
                outbox.delete(item)
                RemoteSyncOutcome.SUCCESS
            } else {
                RemoteSyncOutcome.RETRY
            }
        }
        val invoice = invoiceRepository.getInvoiceById(item.recordId) ?: return RemoteSyncOutcome.SUCCESS.also { outbox.delete(item) }
        if (invoice.imagenUri.isNullOrBlank()) return RemoteSyncOutcome.SUCCESS.also { outbox.delete(item) }
        if (!invoice.driveUploadPending && invoice.driveFileId.isNullOrBlank()) {
            return RemoteSyncOutcome.SUCCESS.also { outbox.delete(item) }
        }
        val result = outbox.withCurrent(item) { invoiceDriveService.upload(invoice) }
            ?: return RemoteSyncOutcome.SUCCESS
        return if (result.uploaded) {
            outbox.delete(item); RemoteSyncOutcome.SUCCESS
        } else if (!result.invoice.driveUploadPending) {
            outbox.delete(item); RemoteSyncOutcome.SUCCESS
        } else RemoteSyncOutcome.RETRY
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
