package com.gastos.feature.backup

import com.google.api.services.sheets.v4.model.*

internal data class LegacySheetDate(val timestamp: Long) {
    fun matches(value: Any?): Boolean {
        val day = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.ROOT).format(java.util.Date(timestamp))
        if (value?.toString() == day) return true
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        val utc = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear(); set(calendar.get(java.util.Calendar.YEAR),calendar.get(java.util.Calendar.MONTH),calendar.get(java.util.Calendar.DAY_OF_MONTH))
        }
        return value?.toString()?.toDoubleOrNull() == utc.timeInMillis / 86_400_000.0 + 25569.0
    }
}
internal data class SheetFormula(val value: String)
internal data class ManagedSheet(val title: String, val headers: List<Any>, val keyIndex: Int,
    val dateColumns: Set<Int> = emptySet(), val rateColumns: Set<Int> = emptySet(), val numberColumns: Set<Int> = emptySet())
internal data class SheetSnapshot(val id: Int, val title: String, val rows: List<List<Any>>, val rowCount: Int = 1000, val columnCount: Int = 26)
internal data class ManagedRecord(val values: List<Any>, val legacyKey: Long? = null, val legacyChecks: Map<Int, Any> = emptyMap())

/** Pure request planner. Only FinAI cells change; personal cells never move with a deleted row. */
internal class SheetsWorkbookPlan(val definition: ManagedSheet, val snapshot: SheetSnapshot) {
    val requests: MutableList<Request> = mutableListOf()
    private val headers: List<String> = snapshot.rows.firstOrNull().orEmpty().map { it.toString() }
    val columns: List<Int>
    private val usedRows: MutableSet<Int> = mutableSetOf()
    private var nextRow: Int = maxOf(1, snapshot.rows.size)
    init {
        var nextColumn: Int = headers.size
        columns = definition.headers.mapIndexed { index, name ->
            val aliases: Set<String> = when (name) {
                "Total del documento" -> setOf("Total del documento", "Total antes de retención")
                "UUID" -> setOf("UUID", "ID")
                "Document UUID" -> setOf("Document UUID", "InvoiceID")
                "Product UUID" -> setOf("Product UUID", "ProductID")
                else -> setOf(name.toString())
            }
            val matches: List<Int> = headers.indices.filter { headers[it] in aliases }
            check(matches.size <= 1) { "SHEETS_AMBIGUOUS_COLUMNS: ${definition.title}" }
            matches.firstOrNull() ?: nextColumn++
        }
        if ((columns.maxOrNull()?.plus(1) ?: 0) > snapshot.columnCount) {
            requests += Request().setAppendDimension(AppendDimensionRequest().setSheetId(snapshot.id).setDimension("COLUMNS")
                .setLength((columns.maxOrNull()!! + 1) - snapshot.columnCount))
        }
        definition.headers.forEachIndexed { index, value -> put(0, index, value) }
    }
    fun put(row: Int, column: Int, value: Any?) {
        val format: NumberFormat? = when {
            row == 0 -> null
            column in definition.dateColumns -> NumberFormat().setType("DATE").setPattern("yyyy-mm-dd")
            column in definition.rateColumns -> NumberFormat().setType("NUMBER").setPattern("0.000000")
            column in definition.numberColumns -> NumberFormat().setType("NUMBER").setPattern("#,##0.00")
            else -> null
        }
        val cell: CellData = CellData().setUserEnteredValue(when (value) {
            null, "" -> null
            is SheetFormula -> ExtendedValue().setFormulaValue(value.value)
            is Number -> ExtendedValue().setNumberValue(value.toDouble())
            is Boolean -> ExtendedValue().setBoolValue(value)
            else -> ExtendedValue().setStringValue(value.toString())
        })
        if (format != null) cell.userEnteredFormat = CellFormat().setNumberFormat(format)
        if (row == 0) cell.userEnteredFormat = CellFormat().setTextFormat(TextFormat().setBold(true))
        requests += Request().setUpdateCells(UpdateCellsRequest().setStart(GridCoordinate().setSheetId(snapshot.id)
            .setRowIndex(row).setColumnIndex(columns[column])).setRows(listOf(RowData().setValues(listOf(cell))))
            .setFields("userEnteredValue" + if (format != null) ",userEnteredFormat.numberFormat" else if (row == 0) ",userEnteredFormat.textFormat.bold" else ""))
    }
    fun upsert(record: ManagedRecord) {
        require(record.values.size == definition.headers.size)
        val key: String = record.values[definition.keyIndex].toString()
        require(key.isNotBlank())
        val keyColumn: Int = columns[definition.keyIndex]
        val matching: List<Int> = snapshot.rows.indices.drop(1).filter { index ->
            snapshot.rows[index].getOrNull(keyColumn)?.toString() == key
        }
        check(matching.size <= 1) { "SHEETS_DUPLICATE_UUID" }
        val legacyRow: Int? = if (matching.isEmpty() && record.legacyKey != null) {
            snapshot.rows.indices.drop(1).filter { index ->
                snapshot.rows[index].getOrNull(keyColumn)?.toString()?.toDoubleOrNull() == record.legacyKey.toDouble()
            }.also { check(it.size <= 1) { "SHEETS_AMBIGUOUS_LEGACY_ID" } }.firstOrNull()?.also { row ->
                check(record.legacyChecks.isNotEmpty() && record.legacyChecks.all { (column, expected) ->
                    val actual: Any? = snapshot.rows[row].getOrNull(columns[column])
                    when (expected) {
                        is LegacySheetDate -> expected.matches(actual)
                        is Number -> actual?.toString()?.toDoubleOrNull() == expected.toDouble()
                        else -> actual?.toString() == expected.toString()
                    }
                }) { "SHEETS_LEGACY_IDENTITY_REVIEW" }
            }
        } else null
        val row: Int = matching.firstOrNull() ?: legacyRow ?: nextRow++
        usedRows += row
        record.values.forEachIndexed { column, value -> put(row, column, value) }
    }
    fun clearWhere(predicate: (List<Any>) -> Boolean) {
        snapshot.rows.forEachIndexed { index, raw ->
            if (index > 0 && index !in usedRows && predicate(columns.map { raw.getOrNull(it) ?: "" })) {
                definition.headers.indices.forEach { put(index, it, null) }
                usedRows += index
            }
        }
    }
    fun hasUnmatchedLegacyRows(): Boolean = snapshot.rows.indices.drop(1).any { index ->
        index !in usedRows && snapshot.rows[index].getOrNull(columns[definition.keyIndex])?.toString()?.toDoubleOrNull() != null
    }
    fun finish(): List<Request> {
        if (nextRow > snapshot.rowCount) requests.add(0, Request().setAppendDimension(AppendDimensionRequest()
            .setSheetId(snapshot.id).setDimension("ROWS").setLength(nextRow - snapshot.rowCount)))
        return requests
    }
    fun range(index: Int): String = "'${definition.title.replace("'", "''")}'!${letter(columns[index])}2:${letter(columns[index])}"
    companion object {
        fun letter(index: Int): String {
            var number: Int = index + 1
            var result: String = ""
            while (number > 0) { number--; result = ('A' + number % 26) + result; number /= 26 }
            return result
        }
    }
}
