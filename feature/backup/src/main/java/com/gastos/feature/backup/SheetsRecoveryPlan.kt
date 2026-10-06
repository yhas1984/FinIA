package com.gastos.feature.backup

import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.model.*
import java.security.MessageDigest

data class SheetsRecoveryInfo(val fileName: String, val createdAt: Long)
data class SheetsRecoveryPreview(val accountKey: String, val workbookId: String, val snapshot: SheetsRecoveryInfo,
    val currentSignature: String, val restoredRows: Int, val removedRows: Int)

/** Values are restored by document identity into current managed columns. No sheet/row is deleted. */
internal object SheetsRecoveryPlan {
    data class Plan(val requests: List<Request>, val restoredRows: Int, val removedRows: Int)

    fun signature(book: Spreadsheet): String = MessageDigest.getInstance("SHA-256")
        .digest(GsonFactory.getDefaultInstance().toString(book).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun build(saved: Spreadsheet, current: Spreadsheet, locale: SheetsSchema.LocaleCode): Plan {
        check(saved.spreadsheetId == current.spreadsheetId) { "SHEETS_RECOVERY_WRONG_BOOK" }
        val requests = mutableListOf<Request>()
        var restored = 0
        var removed = 0
        for (definition in managedSheetDefinitions(locale)) {
            val before = saved.sheets.orEmpty().singleOrNull { it.properties.title == definition.title }
                ?: error("SHEETS_RECOVERY_INCOMPATIBLE")
            val now = current.sheets.orEmpty().singleOrNull { it.properties.title == definition.title }
                ?: error("SHEETS_RECOVERY_INCOMPATIBLE")
            val sourceRows = rows(before)
            val targetRows = rows(now)
            val source = SheetsWorkbookPlan(definition, snapshot(before, sourceRows))
            check(source.columns.all { it < sourceRows.firstOrNull().orEmpty().size }) { "SHEETS_RECOVERY_INCOMPATIBLE" }
            val target = SheetsWorkbookPlan(definition, snapshot(now, targetRows))
            check(target.columns.all { it < targetRows.firstOrNull().orEmpty().size }) { "SHEETS_RECOVERY_INCOMPATIBLE" }
            val keys = mutableSetOf<String>()
            sourceRows.drop(1).forEach { raw ->
                val row = source.columns.map { raw.getOrNull(it) ?: "" }
                val key = row[definition.keyIndex].toString()
                if (ownedKey(key)) {
                    check(keys.add(key)) { "SHEETS_DUPLICATE_UUID" }
                    target.upsert(ManagedRecord(row))
                    restored++
                }
            }
            target.clearWhere { row ->
                val key = row[definition.keyIndex].toString()
                (ownedKey(key) && key !in keys).also { if (it) removed++ }
            }
            requests += target.finish()
        }
        return Plan(requests, restored, removed)
    }

    private fun ownedKey(key: String): Boolean = key.length >= 36 && runCatching {
        java.util.UUID.fromString(key.take(36)).toString().equals(key.take(36), ignoreCase = true)
    }.getOrDefault(false)

    private fun snapshot(sheet: Sheet, rows: List<List<Any>>) = SheetSnapshot(sheet.properties.sheetId,
        sheet.properties.title, rows, sheet.properties.gridProperties.rowCount, sheet.properties.gridProperties.columnCount)

    private fun rows(sheet: Sheet): List<List<Any>> {
        val rows = mutableListOf<MutableList<Any>>()
        sheet.data.orEmpty().forEach { grid ->
            grid.rowData.orEmpty().forEachIndexed { index, data ->
                val rowIndex = (grid.startRow ?: 0) + index
                while (rows.size <= rowIndex) rows.add(mutableListOf())
                data.getValues().orEmpty().forEachIndexed { column, cell ->
                    val col = (grid.startColumn ?: 0) + column
                    while (rows[rowIndex].size <= col) rows[rowIndex].add("")
                    val value = cell.userEnteredValue
                    rows[rowIndex][col] = when {
                        value?.formulaValue != null -> SheetFormula(value.formulaValue)
                        value?.numberValue != null -> value.numberValue
                        value?.boolValue != null -> value.boolValue
                        else -> value?.stringValue.orEmpty()
                    }
                }
            }
        }
        return rows
    }
}
