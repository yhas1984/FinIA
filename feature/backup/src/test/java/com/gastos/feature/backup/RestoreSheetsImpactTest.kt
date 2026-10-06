package com.gastos.feature.backup

import com.gastos.domain.model.*
import com.gastos.repository.BackupDataset
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RestoreSheetsImpactTest {
    @Test fun `restoring income with the same UUID only authorizes removal of the former expense`() = runTest {
        val expense = Invoice(id=1,fecha=1,proveedor="Expense",total=10.0,tipo=InvoiceType.GASTO)
        val income = Income(id=1,documentUuid=expense.documentUuid,fecha=1,concepto="Income",monto=10.0)
        val previous = BackupDataset(listOf(expense),emptyList(),listOf(income),emptyList(),emptyList())
        val incoming = previous.copy(invoices=emptyList())
        val removals = restoreSheetRemovals(previous,incoming,"account" to "book")
        assertEquals(RemoteSyncTarget.EXPENSE_SHEETS.name,removals.single().target)
        assertEquals(expense.documentUuid,removals.single().documentUuid)
        val dao = mockk<RemoteSyncOutboxDao>(relaxed=true) { coEvery { pending() } returns emptyList() }
        RemoteSyncOutboxRepository(dao,mockk(relaxed=true)).reconcile(incoming,sheetDeletes=listOf(
            RemoteSyncSheetDelete(RemoteSyncTarget.EXPENSE_SHEETS,1,expense.documentUuid,"account","book")))
        coVerify { dao.replaceAll(match { rows ->
            rows.any { it.target == RemoteSyncTarget.EXPENSE_SHEETS && it.action == RemoteSyncAction.DELETE } &&
                rows.any { it.target == RemoteSyncTarget.INCOME_SHEETS && it.action == RemoteSyncAction.UPSERT }
        }) }
    }
    @Test fun `restore compares stable identity not recycled local ids and includes historical income`() {
        val expense = Invoice(id=1,documentUuid="removed-expense",fecha=1,proveedor="A",total=10.0,tipo=InvoiceType.GASTO)
        val historical = expense.copy(id=2,documentUuid="kept-income",tipo=InvoiceType.INGRESO)
        val previous = BackupDataset(listOf(expense,historical),emptyList(),emptyList(),emptyList(),emptyList())
        val incoming = previous.copy(invoices=listOf(expense.copy(documentUuid="new-expense"), historical.copy(id=99)))
        val result = restoreSheetRemovals(previous,incoming,"account" to "same-book")
        assertEquals(listOf("removed-expense"),result.map { it.documentUuid })
        assertEquals("same-book",result.single().spreadsheetId)
        assertEquals("account",result.single().accountId)
        assertTrue(restoreSheetRemovals(previous,incoming,null).isEmpty())
    }

    @Test fun `restore enqueues only authorized UUID scoped Sheets removals and never Drive deletes`() = runTest {
        val dao = mockk<RemoteSyncOutboxDao>(relaxed=true) { coEvery { pending() } returns emptyList() }
        val repo = RemoteSyncOutboxRepository(dao,mockk(relaxed=true))
        val data = BackupDataset(emptyList(),emptyList(),emptyList(),emptyList(),emptyList())
        repo.reconcile(data, sheetDeletes=listOf(
            RemoteSyncSheetDelete(RemoteSyncTarget.EXPENSE_SHEETS,1,"uuid","account","book"),
            RemoteSyncSheetDelete(RemoteSyncTarget.INCOME_SHEETS,2),
            RemoteSyncSheetDelete(RemoteSyncTarget.INVOICE_DRIVE,3,"uuid","account","book")),
            driveDeletes=listOf(RemoteSyncDriveDelete(1,"photo")))
        coVerify { dao.replaceAll(match { rows -> rows.size == 1 && rows.single().let {
            it.target == RemoteSyncTarget.EXPENSE_SHEETS && it.action == RemoteSyncAction.DELETE &&
                it.documentUuid == "uuid" && it.accountId == "account" && it.spreadsheetId == "book"
        } }) }
    }
}
