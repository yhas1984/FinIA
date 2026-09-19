package com.gastos.feature.backup

import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.repository.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RemoteSyncIsolationTest {
    @Test fun `one failed photograph does not stop an independent Sheets operation`() = runTest {
        val invoice = Invoice(id = 1, fecha = 1, proveedor = "Synthetic", tipo = InvoiceType.GASTO, total = 1.0)
        val image = RemoteSyncOutboxEntity("image", RemoteSyncTarget.INVOICE_DRIVE, 1, RemoteSyncAction.UPSERT, documentUuid = invoice.documentUuid)
        val sheet = RemoteSyncOutboxEntity("sheet", RemoteSyncTarget.EXPENSE_SHEETS, 2, RemoteSyncAction.UPSERT)
        val outbox = mockk<RemoteSyncOutboxRepository>(relaxed = true)
        coEvery { outbox.pending() } returns listOf(image, sheet)
        coEvery { outbox.withCurrent<Boolean>(any(), any()) } coAnswers { secondArg<suspend () -> Boolean>().invoke() }
        val invoices = mockk<InvoiceRepository> { coEvery { getInvoiceById(1) } returns invoice }
        val drive = mockk<InvoiceDriveService> {
            coEvery { upload(any<Invoice>(), any()) } returns InvoiceDriveUploadResult(invoice, false, "SERVER_UNAVAILABLE")
        }
        val sheets = mockk<SheetsSyncManager> { coEvery { performExpenseSync(2) } returns true }
        val worker = RemoteSyncWorker(mockk(relaxed = true), mockk(relaxed = true), outbox, invoices,
            mockk(relaxed = true), drive, sheets, mockk { every { shouldDefer() } returns false })
        worker.doWork()
        coVerify { outbox.failed(image, "SERVER_UNAVAILABLE", true, false, any()) }
        coVerify { sheets.performExpenseSync(2) }
        coVerify { outbox.delete(sheet) }
        coVerify(exactly = 0) { outbox.delete(image) }
    }
    @Test fun `retries are per-item bounded and offline does not consume an attempt`() = runTest {
        val dao = mockk<RemoteSyncOutboxDao>(relaxed = true)
        val repository = RemoteSyncOutboxRepository(dao, mockk(relaxed = true))
        val item = RemoteSyncOutboxEntity("a", RemoteSyncTarget.INCOME_DRIVE, 1, RemoteSyncAction.UPSERT)
        repository.failed(item, "OFFLINE", consumeAttempt = false, now = 100)
        coVerify { dao.updateFailure("a", item.operationId, 0, 30_100L, RemoteSyncStatus.PENDING, "OFFLINE") }
        repository.failed(item, "AUTH_REQUIRED", consumeAttempt = false, now = 100)
        coVerify { dao.updateFailure("a", item.operationId, 0, 30_100L, RemoteSyncStatus.WAITING_AUTH, "AUTH_REQUIRED") }
        RemoteSyncOutboxRepository.RETRY_DELAYS.forEachIndexed { index, delay ->
            repository.failed(item.copy(attempts = index), "SERVER", now = 100)
            coVerify { dao.updateFailure("a", item.operationId, index + 1, 100 + delay, RemoteSyncStatus.PENDING, "SERVER") }
        }
        repository.failed(item.copy(attempts = 5), "SERVER", now = 100)
        coVerify { dao.updateFailure("a", item.operationId, 6, 100, RemoteSyncStatus.FAILED, "SERVER") }
    }
    @Test fun `image delete requires exact file account and explicit consent`() = runTest {
        val dao = mockk<RemoteSyncOutboxDao>(relaxed = true)
        val repository = RemoteSyncOutboxRepository(dao, mockk(relaxed = true))
        repository.enqueue(RemoteSyncTarget.INVOICE_DRIVE, 1, RemoteSyncAction.DELETE, "file")
        repository.enqueue(RemoteSyncTarget.INCOME_DRIVE, 1, RemoteSyncAction.DELETE, "file", deleteConsent = true)
        coVerify(exactly = 0) { dao.upsert(any()) }
        repository.enqueue(RemoteSyncTarget.INCOME_DRIVE, 1, RemoteSyncAction.DELETE, "file", "uuid", "account", true)
        coVerify { dao.upsert(match { it.remoteFileId == "file" && it.accountId == "account" && it.deleteConsent }) }
    }
    @Test fun `restore ignores legacy deletion journal and discovers old income photographs`() = runTest {
        val dao = mockk<RemoteSyncOutboxDao>(relaxed = true)
        coEvery { dao.pending() } returns emptyList()
        val repository = RemoteSyncOutboxRepository(dao, mockk(relaxed = true))
        val income = com.gastos.domain.model.Income(id = 1, fecha = 1, concepto = "A", monto = 10.0, imagenUri = "local")
        repository.reconcile(BackupDataset(emptyList(), emptyList(), listOf(income), emptyList(), emptyList()),
            driveDeletes = listOf(RemoteSyncDriveDelete(1, "legacy-file")))
        coVerify { dao.replaceAll(match { rows -> rows.any { it.target == RemoteSyncTarget.INCOME_DRIVE && it.documentUuid == income.documentUuid } && rows.none { it.action == RemoteSyncAction.DELETE } }) }
    }
}
