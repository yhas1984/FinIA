package com.gastos.feature.backup

import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.InvoiceType.GASTO
import com.gastos.repository.IncomeRepository
import com.gastos.repository.InvoiceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteSyncProcessorTest {
    @Test
    fun `income upsert waits for awaited manager call and clears outbox on success`() = runTest {
        val outbox = mockk<RemoteSyncOutboxRepository>(relaxed = true)
        val invoiceRepo = mockk<InvoiceRepository>()
        val incomeRepo = mockk<IncomeRepository>()
        val drive = mockk<InvoiceDriveService>(relaxed = true)
        val sheets = mockk<SheetsSyncManager>()
        coEvery { incomeRepo.getIncomeById(7) } returns income()
        coEvery { outbox.withCurrent<Boolean>(any(), any()) } coAnswers {
            secondArg<suspend () -> Boolean>().invoke()
        }
        coEvery { sheets.performIncomeUpsert(7) } returns true
        val processor = RemoteSyncProcessor(outbox, invoiceRepo, incomeRepo, drive, sheets)
        val outcome = processor.process(RemoteSyncOutboxEntity("INCOME_SHEETS:7", RemoteSyncTarget.INCOME_SHEETS, 7, RemoteSyncAction.UPSERT))
        assertEquals(RemoteSyncOutcome.SUCCESS, outcome)
        coVerify(exactly = 1) { outbox.delete(match { it.targetKey == "INCOME_SHEETS:7" }) }
        coVerify(exactly = 1) { sheets.performIncomeUpsert(7) }
    }

    @Test
    fun `missing income delete still dispatches delete and clears outbox on success`() = runTest {
        val outbox = mockk<RemoteSyncOutboxRepository>(relaxed = true)
        val invoiceRepo = mockk<InvoiceRepository>(relaxed = true)
        val incomeRepo = mockk<IncomeRepository>()
        val drive = mockk<InvoiceDriveService>(relaxed = true)
        val sheets = mockk<SheetsSyncManager>()
        coEvery { incomeRepo.getIncomeById(7) } returns null
        coEvery { outbox.withCurrent<Boolean>(any(), any()) } coAnswers {
            secondArg<suspend () -> Boolean>().invoke()
        }
        coEvery { sheets.performIncomeDelete(7) } returns true
        val processor = RemoteSyncProcessor(outbox, invoiceRepo, incomeRepo, drive, sheets)
        val outcome = processor.process(RemoteSyncOutboxEntity("INCOME_SHEETS:7", RemoteSyncTarget.INCOME_SHEETS, 7, RemoteSyncAction.DELETE))
        assertEquals(RemoteSyncOutcome.SUCCESS, outcome)
        coVerify(exactly = 1) { outbox.delete(match { it.targetKey == "INCOME_SHEETS:7" }) }
        coVerify(exactly = 1) { sheets.performIncomeDelete(7) }
    }

    @Test
    fun `failed drive upload with pending stays for retry`() = runTest {
        val outbox = mockk<RemoteSyncOutboxRepository>(relaxed = true)
        val invoiceRepo = mockk<InvoiceRepository>()
        val incomeRepo = mockk<IncomeRepository>(relaxed = true)
        val drive = mockk<InvoiceDriveService>()
        val sheets = mockk<SheetsSyncManager>(relaxed = true)
        coEvery { invoiceRepo.getInvoiceById(3) } returns Invoice(id = 3, fecha = 0, proveedor = "p", tipo = GASTO, moneda = "EUR", total = 1.0, ivaPercent = 0.0, irpfPercent = 0.0, paisCodigo = "ES", imagenUri = "content://x", driveUploadPending = true)
        val uploadResult = InvoiceDriveUploadResult(invoice = Invoice(id = 3, fecha = 0, proveedor = "p", tipo = GASTO, moneda = "EUR", total = 1.0, ivaPercent = 0.0, irpfPercent = 0.0, paisCodigo = "ES", imagenUri = "content://x", driveUploadPending = true), uploaded = false, message = "x")
        coEvery { outbox.withCurrent<InvoiceDriveUploadResult>(any(), any()) } coAnswers {
            secondArg<suspend () -> InvoiceDriveUploadResult>().invoke()
        }
        coEvery { drive.upload(any()) } returns uploadResult
        val processor = RemoteSyncProcessor(outbox, invoiceRepo, incomeRepo, drive, sheets)
        assertEquals(RemoteSyncOutcome.RETRY, processor.process(RemoteSyncOutboxEntity("INVOICE_DRIVE:3", RemoteSyncTarget.INVOICE_DRIVE, 3, RemoteSyncAction.UPSERT)))
        coVerify(exactly = 0) { outbox.delete(any()) }
    }

    private fun income() = Income(id = 7, fecha = 0, concepto = "s", monto = 1.0, totalDevengado = 1.0, totalNeto = 1.0, moneda = "EUR")
}
