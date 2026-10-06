package com.gastos.feature.backup

import com.google.api.services.sheets.v4.model.*
import org.junit.Assert.*
import org.junit.Test

class SheetsRecoveryPlanTest {
    private val uuid = "00000000-0000-4000-8000-000000000001"
    private fun book(personal: Boolean, withRecord: Boolean): Spreadsheet = Spreadsheet().setSpreadsheetId("same").setSheets(
        managedSheetDefinitions(SheetsSchema.LocaleCode.ES).mapIndexed { id, definition ->
            val headers = definition.headers.toMutableList()
            val row = MutableList<Any>(headers.size) { "" }
            row[definition.keyIndex] = if(id < 2) uuid else "$uuid:product:1"
            if(personal) { headers.add(1,"Personal"); row.add(1,SheetFormula("=2+2")) }
            val rows = listOf(headers) + if(withRecord) listOf(row) else emptyList()
            Sheet().setProperties(SheetProperties().setSheetId(id).setTitle(definition.title)
                .setGridProperties(GridProperties().setColumnCount(50).setRowCount(1000)))
                .setData(listOf(GridData().setRowData(rows.map { values -> RowData().setValues(values.map { value ->
                    CellData().setNote(if(value is SheetFormula) "private" else null).setUserEnteredValue(
                        if(value is SheetFormula) ExtendedValue().setFormulaValue(value.value) else ExtendedValue().setStringValue(value.toString()))
                }) })))
        })

    @Test fun `recovery maps managed headers while keeping interleaved personal formulas and notes untouched`() {
        val current = book(personal=true,withRecord=true)
        val signature = SheetsRecoveryPlan.signature(current)
        val plan = SheetsRecoveryPlan.build(book(false,true),current,SheetsSchema.LocaleCode.ES)
        assertEquals(4,plan.restoredRows)
        assertEquals(0,plan.removedRows)
        assertEquals(signature,SheetsRecoveryPlan.signature(current))
        assertTrue(plan.requests.none { it.deleteSheet != null || it.deleteDimension != null || it.setBasicFilter != null })
        assertTrue(plan.requests.mapNotNull { it.updateCells }.none { it.start.columnIndex == 1 })
        assertTrue(plan.requests.mapNotNull { it.updateCells }.all { !it.fields.contains("note") })
    }

    @Test fun `only owned rows absent from snapshot are cleared and wrong book is rejected`() {
        val plan = SheetsRecoveryPlan.build(book(false,false),book(true,true),SheetsSchema.LocaleCode.ES)
        assertEquals(4,plan.removedRows)
        assertEquals(0,plan.restoredRows)
        assertTrue(runCatching { SheetsRecoveryPlan.build(book(false,true).setSpreadsheetId("other"),book(true,true),SheetsSchema.LocaleCode.ES) }.isFailure)
    }
}
