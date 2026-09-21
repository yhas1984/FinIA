@file:Suppress("DEPRECATION")
package com.gastos.feature.backup

import com.gastos.domain.model.*
import com.gastos.repository.*
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SheetsSyncSafetyTest {
    @Test fun `one tap commits the entire snapshot and only deletes from the linked destination`() = runTest {
        val f = Fixture()
        val expense = Invoice(id=1, fecha=1, proveedor="Synthetic", tipo=InvoiceType.GASTO, total=12.1)
        val historical = expense.copy(id=2, documentUuid="legacy-income", tipo=InvoiceType.INGRESO)
        val income = Income(id=3, fecha=1, concepto="Synthetic", monto=2.0)
        val product = Product(id=1, invoiceId=1, descripcion="Test", cantidad=1.0, precioUnitario=10.0, subtotal=10.0)
        val data = BackupDataset(listOf(expense, historical), listOf(product), listOf(income), emptyList(), emptyList())
        coEvery { f.snapshots.financialSnapshot() } returns data
        val otherBook = f.item.copy(targetKey="other", spreadsheetId="other-book")
        coEvery { f.outbox.pending() } returnsMany listOf(listOf(f.item,otherBook),listOf(otherBook))
        assertEquals(0, f.manager.syncChanges())
        coVerify(exactly=1) { f.export.syncDocuments(f.account,"book",data.invoices,data.incomes,data.products,setOf("old-uuid")) }
        coVerify { f.outbox.delete(f.item) }
        coVerify(exactly=0) { f.outbox.delete(otherBook) }
        coVerify(exactly=0) { f.export.exportToSheets(any(),any(),any(),any(),any()) }
        assertTrue(f.manager.currentOperations(listOf(otherBook)).isEmpty())
    }

    @Test fun `a failed batch stays queued and is never acknowledged as synchronized`() = runTest {
        val f = Fixture()
        coEvery { f.snapshots.financialSnapshot() } returns BackupDataset(emptyList(),emptyList(),emptyList(),emptyList(),emptyList())
        coEvery { f.outbox.pending() } returns listOf(f.item)
        coEvery { f.export.syncDocuments(any(),any(),any(),any(),any(),any()) } throws java.net.SocketTimeoutException("synthetic")
        assertTrue(runCatching { f.manager.syncChanges() }.isFailure)
        coVerify(exactly=0) { f.outbox.delete(any()) }
        coVerify(exactly=0) { f.links.recordFingerprint(any(),any(),any(),any()) }
        coVerify { f.outbox.failed(f.item,"TRANSIENT",false,any(),any()) }
    }

    @Test fun `an edit queued during the remote request is not acknowledged by the earlier snapshot`() = runTest {
        val f = Fixture()
        val invoice = Invoice(id=1,documentUuid="old-uuid",fecha=1,proveedor="Test",tipo=InvoiceType.GASTO,total=1.0)
        coEvery { f.snapshots.financialSnapshot() } returns BackupDataset(listOf(invoice),emptyList(),emptyList(),emptyList(),emptyList())
        val original = f.item.copy(action=RemoteSyncAction.UPSERT)
        val later = original.copy(operationId="later-operation")
        var pending = listOf(original)
        coEvery { f.outbox.pending() } answers { pending }
        coEvery { f.export.syncDocuments(any(),any(),any(),any(),any(),any()) } coAnswers { pending = listOf(later) }
        assertEquals(1,f.manager.syncChanges())
        coVerify { f.outbox.delete(original) }
        coVerify(exactly=0) { f.outbox.delete(later) }
    }

    private class Fixture {
        val account = mockk<GoogleSignInAccount> { every { id } returns "account"; every { email } returns "synthetic@example.test" }
        val export = mockk<SheetsExportService>(relaxed=true) {
            every { getLastSignedInAccount() } returns account
            every { isSignedIn() } returns true
            coEvery { schemaVersion(any(),any()) } returns 9
        }
        val invoices = mockk<InvoiceRepository>(relaxed=true)
        val incomes = mockk<IncomeRepository>(relaxed=true)
        val products = mockk<ProductRepository> { every { getProductsByInvoiceId(any()) } returns flowOf(emptyList()) }
        val outbox = mockk<RemoteSyncOutboxRepository>(relaxed=true) {
            coEvery { isCurrent(any()) } returns true
            coEvery { bindSheets(any(),any(),any(),any()) } coAnswers {
                firstArg<RemoteSyncOutboxEntity>().copy(accountId=secondArg(),spreadsheetId=thirdArg(),documentUuid=arg(3))
            }
        }
        val links = mockk<SheetsLinkStore>(relaxed=true) { every { getSpreadsheetId(any()) } returns "book" }
        val currency = mockk<CurrencyPreference> { every { defaultCurrency } returns MutableStateFlow("EUR") }
        val rates = mockk<ExchangeRateProvider> {
            every { rates } returns MutableStateFlow(mapOf("EUR" to 0.9, "USD" to 1.0))
            every { lastUpdated } returns MutableStateFlow(123L)
        }
        val snapshots = mockk<BackupDataRepository> {
            coEvery { documentSnapshot(any(),any()) } coAnswers {
                val isIncome = firstArg<Boolean>()
                val id = secondArg<Long>()
                BackupDataset(if (isIncome) emptyList() else listOfNotNull(invoices.getInvoiceById(id)), emptyList(),
                    if (isIncome) listOfNotNull(incomes.getIncomeById(id)) else emptyList(),emptyList(),emptyList())
            }
        }
        val manager = SheetsSyncManager(mockk(relaxed=true),mockk { every { isPremium } returns MutableStateFlow(true) },
            export,invoices,incomes,products,rates,currency,
            links,SheetsOperationCoordinator(),outbox,mockk(relaxed=true),snapshots)
        val item = RemoteSyncOutboxEntity("key",RemoteSyncTarget.EXPENSE_SHEETS,1,RemoteSyncAction.DELETE,
            documentUuid="old-uuid",accountId=SheetsLinkStore.getAccountPreferenceKey("account",null),spreadsheetId="book")
    }
    @Test fun `changing account or workbook cannot redirect a deletion`() = runTest {
        val f = Fixture()
        assertFalse(f.manager.process(f.item.copy(accountId="different")))
        assertFalse(f.manager.process(f.item.copy(spreadsheetId="different")))
        coVerify(exactly=0) { f.export.syncDocument(any(),any(),any(),any(),any(),any()) }
    }
    @Test fun `prepared deletion surviving a process interruption is recovered by UUID`() = runTest {
        val f = Fixture()
        coEvery { f.invoices.getInvoiceById(1) } returns null
        val prepared = f.item.copy(status=RemoteSyncStatus.PREPARED)
        assertTrue(f.manager.process(prepared))
        coVerify { f.outbox.confirmPrepared(prepared) }
        coVerify { f.export.syncDocument(f.account,"book",emptyList(),emptyList(),emptyList(),"old-uuid") }
    }
    @Test fun `prepared deletion with a surviving local record is discarded`() = runTest {
        val f = Fixture()
        coEvery { f.invoices.getInvoiceById(1) } returns Invoice(documentUuid="old-uuid",id=1,fecha=1,proveedor="Synthetic",tipo=InvoiceType.GASTO,total=1.0)
        val prepared = f.item.copy(status=RemoteSyncStatus.PREPARED)
        assertTrue(f.manager.process(prepared))
        coVerify { f.outbox.delete(prepared) }
        coVerify(exactly=0) { f.export.syncDocument(any(),any(),any(),any(),any(),any()) }
    }
    @Test fun `recycled local id never updates a different UUID`() = runTest {
        val f = Fixture()
        coEvery { f.invoices.getInvoiceById(1) } returns Invoice(documentUuid="new-uuid",id=1,fecha=1,proveedor="Synthetic",tipo=InvoiceType.GASTO,total=1.0)
        assertTrue(f.manager.process(f.item.copy(action=RemoteSyncAction.UPSERT)))
        coVerify(exactly=0) { f.export.syncDocument(any(),any(),any(),any(),any(),any()) }
    }
    @Test fun `newer schema fails safely without reexporting`() = runTest {
        val f = Fixture()
        coEvery { f.invoices.getInvoiceById(1) } returns null
        coEvery { f.export.schemaVersion(any(),any()) } returns 10
        assertTrue(runCatching { f.manager.process(f.item) }.isFailure)
        coVerify(exactly=0) { f.export.exportToSheets(any(),any(),any(),any(),any()) }
        coVerify(exactly=0) { f.export.syncDocument(any(),any(),any(),any(),any(),any()) }
    }
    @Test fun `confirmed unchanged documents skip remote writes but edits sync again`() = runTest {
        val f = Fixture()
        var invoice = Invoice(documentUuid="old-uuid",id=1,fecha=1,proveedor="Synthetic",tipo=InvoiceType.GASTO,total=1.0)
        coEvery { f.invoices.getInvoiceById(1) } answers { invoice }
        var acknowledged: String? = null
        every { f.links.fingerprint(any(),any(),any()) } answers { acknowledged }
        every { f.links.recordFingerprint(any(),any(),any(),any()) } answers { acknowledged = arg(3) }
        val upsert = f.item.copy(action=RemoteSyncAction.UPSERT)
        assertTrue(f.manager.process(upsert))
        assertNotNull(acknowledged)
        assertTrue(f.manager.process(upsert))
        coVerify(exactly=1) { f.export.syncDocument(any(),any(),any(),any(),any(),any()) }
        invoice = invoice.copy(total=2.0)
        assertTrue(f.manager.process(upsert))
        coVerify(exactly=2) { f.export.syncDocument(any(),any(),any(),any(),any(),any()) }
    }

    @Test fun `local deletion stays successful if marking the durable intent confirmed fails`() = runTest {
        val f = Fixture()
        val invoice = Invoice(documentUuid="old-uuid",id=1,fecha=1,proveedor="Synthetic",tipo=InvoiceType.GASTO,total=1.0)
        coEvery { f.outbox.prepareDelete(any(),any(),any(),any(),any()) } returns f.item.copy(status=RemoteSyncStatus.PREPARED)
        coEvery { f.outbox.confirmPrepared(any()) } throws java.io.IOException("synthetic disk failure")
        mockkObject(com.gastos.extension.SafeLog)
        every { com.gastos.extension.SafeLog.w(any(),any()) } returns 0
        try { f.manager.deleteLocal(invoice) } finally { unmockkObject(com.gastos.extension.SafeLog) }
        coVerify { f.invoices.deleteInvoice(invoice) }
        coVerify(exactly=0) { f.outbox.delete(any()) }
    }

}
