package com.gastos.feature.backup

import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.mergeIncomes
import com.gastos.repository.BackupDataset

data class RestoreSheetsImpact(val accountKey: String, val workbookId: String, val expenses: Int, val incomes: Int)

/** Numeric IDs may repeat between installations. Only stable financial identities are compared. */
internal fun restoreSheetRemovals(previous: BackupDataset, incoming: BackupDataset,
    destination: Pair<String, String>?): List<RestoreJournalSheetRow> {
    if (destination == null) return emptyList()
    val expenses = incoming.invoices.filter { it.tipo == InvoiceType.GASTO }.map { it.documentUuid }.toSet()
    val incomes = mergeIncomes(incoming.invoices, incoming.incomes).map { it.documentUuid }.toSet()
    return previous.invoices.filter { it.tipo == InvoiceType.GASTO && it.documentUuid !in expenses }.map {
        RestoreJournalSheetRow(RemoteSyncTarget.EXPENSE_SHEETS.name, it.id, it.documentUuid, destination.first, destination.second)
    } + mergeIncomes(previous.invoices, previous.incomes).filter { it.documentUuid !in incomes }.map {
        RestoreJournalSheetRow(RemoteSyncTarget.INCOME_SHEETS.name, it.id, it.documentUuid, destination.first, destination.second)
    }
}
