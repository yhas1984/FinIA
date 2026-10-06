package com.gastos.feature.backup

import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SheetsAppearancePlanTest {
    private fun book(locale: SheetsSchema.LocaleCode = SheetsSchema.LocaleCode.ES, interleaved: Boolean = false): Spreadsheet {
        val descriptor = SheetsSchema.descriptor(locale)
        val tables = managedSheetDefinitions(locale).mapIndexed { id, definition ->
            val headers = definition.headers.toMutableList().apply { if (interleaved) add(1,"My formula") }
            Sheet().setProperties(SheetProperties().setSheetId(id).setTitle(definition.title)
                .setGridProperties(GridProperties().setRowCount(1000).setColumnCount(40).setFrozenRowCount(2)))
                .setData(listOf(GridData().setRowData(listOf(RowData().setValues(headers.map { CellData().setUserEnteredValue(ExtendedValue().setStringValue(it.toString())) })))))
                .setBasicFilter(BasicFilter().setRange(GridRange().setSheetId(id)).setCriteria(hashMapOf("1" to FilterCriteria().setHiddenValues(listOf("private")))))
        }
        val remaining = listOf(descriptor.resumenTitle, if (locale == SheetsSchema.LocaleCode.ES) "Análisis personal" else "Personal analysis").mapIndexed { index,title ->
            Sheet().setProperties(SheetProperties().setSheetId(index+4).setTitle(title).setGridProperties(GridProperties().setRowCount(1000).setColumnCount(26)))
        }
        return Spreadsheet().setSpreadsheetId("same-book").setProperties(SpreadsheetProperties().setLocale("es_ES")).setSheets(tables+remaining)
    }
    private fun mark(book: Spreadsheet, chart: Int = 41, version: Int = 1) = book.setDeveloperMetadata(listOf(DeveloperMetadata().setMetadataId(7)
        .setMetadataKey(SheetsAppearancePlan.METADATA_KEY).setMetadataValue("$version:$chart")))

    @Test fun `style targets mapped columns and preserves personal columns filters values and formulas`() {
        val source = book(interleaved=true)
        val before = source.toString()
        val requests = SheetsAppearancePlan.requests(source,SheetsSchema.LocaleCode.ES)
        assertEquals(before,source.toString())
        assertTrue(requests.none { it.deleteSheet != null || it.deleteDimension != null || it.moveDimension != null || it.setBasicFilter != null })
        assertTrue(requests.filter { it.repeatCell?.range?.sheetId == 0 }.none { it.repeatCell.range.startColumnIndex == 1 })
        assertTrue(requests.filter { it.updateDimensionProperties?.range?.sheetId == 0 }.none { it.updateDimensionProperties.range.startIndex == 1 })
        assertTrue(requests.filter { it.updateCells != null }.all { it.updateCells.start.sheetId == 5 })
        assertEquals(1,requests.count { it.addChart != null })
        assertEquals(1,requests.count { it.createDeveloperMetadata != null })
    }
    @Test fun `21 remains 21 percent and foreign currency rates remain accessible in both languages`() {
        for (locale in SheetsSchema.LocaleCode.entries) {
            val requests = SheetsAppearancePlan.requests(book(locale),locale)
            val percentages = requests.mapNotNull { it.repeatCell?.cell?.userEnteredFormat?.numberFormat }.filter { it.pattern.contains("%") }
            assertTrue(percentages.isNotEmpty())
            assertTrue(percentages.all { it.pattern == "0.00\" %\"" && it.type == "NUMBER" })
            val hidden = requests.mapNotNull { it.updateDimensionProperties }.filter { it.properties.hiddenByUser == true }
            assertTrue(hidden.none { it.range.sheetId == 0 && it.range.startIndex in setOf(16,17,18,19,20) })
        }
    }
    @Test fun `appearance is skipped after application and never overwrites a newer visual version`() {
        assertTrue(SheetsAppearancePlan.requests(mark(book()),SheetsSchema.LocaleCode.ES).isEmpty())
        assertTrue(runCatching { SheetsAppearancePlan.requests(mark(book(),version=2),SheetsSchema.LocaleCode.ES,true) }.isFailure)
    }
    @Test fun `existing personal notes and formulas prevent the guide and move new chart below occupied area`() {
        val source = book()
        source.sheets[4].data = listOf(GridData().setStartRow(3).setStartColumn(3).setRowData(listOf(RowData().setValues(listOf(CellData().setNote("Personal note"))))))
        source.sheets[5].data = listOf(GridData().setRowData(listOf(RowData().setValues(listOf(CellData().setUserEnteredValue(ExtendedValue().setFormulaValue("=1+1")))))))
        val requests = SheetsAppearancePlan.requests(source,SheetsSchema.LocaleCode.ES)
        assertTrue(requests.none { it.updateCells != null })
        assertTrue(requests.single { it.addChart != null }.addChart.chart.position.overlayPosition.anchorCell.rowIndex >= 12)
    }
    @Test fun `reset updates only owned chart and preserves foreign chart and custom conditional rule`() {
        val source = mark(book())
        source.sheets[4].charts = listOf(EmbeddedChart().setChartId(41),EmbeddedChart().setChartId(99))
        source.sheets[0].conditionalFormats = listOf(ConditionalFormatRule().setBooleanRule(BooleanRule().setCondition(BooleanCondition()
            .setType("CUSTOM_FORMULA").setValues(listOf(ConditionValue().setUserEnteredValue("=A2>10"))))))
        val requests = SheetsAppearancePlan.requests(source,SheetsSchema.LocaleCode.ES,true)
        assertTrue(requests.none { it.addChart != null || it.deleteEmbeddedObject != null || it.deleteConditionalFormatRule != null })
        assertEquals(41,requests.single { it.updateChartSpec != null }.updateChartSpec.chartId.toInt())
        val ranges = requests.single { it.updateChartSpec != null }.updateChartSpec.spec.basicChart
        assertEquals(3,ranges.domains.single().domain.sourceRange.sources.single().startRowIndex.toInt())
        assertEquals(5,ranges.series.single().series.sourceRange.sources.single().endRowIndex.toInt())
    }
    @Test fun `applied timeout verifies marker and does not insert a second chart`() = runTest {
        val api = mockk<Sheets>(relaxed=true)
        val source = book()
        var applied = false
        val batches = mutableListOf<BatchUpdateSpreadsheetRequest>()
        every { api.spreadsheets().get("same-book").setIncludeGridData(false).execute() } answers { if (applied) book().setDeveloperMetadata(listOf(batches.last().requests.single { it.createDeveloperMetadata != null }.createDeveloperMetadata.developerMetadata)) else source }
        every { api.spreadsheets().get("same-book").setIncludeGridData(true).setFields(SheetsRecoverySnapshot.FIELDS).execute() } returns source
        every { api.spreadsheets().batchUpdate("same-book",capture(batches)).execute() } answers { applied=true; throw java.net.SocketTimeoutException("after commit") }
        var backups = 0
        val updater = SheetsAppearanceUpdater(api,"same-book",{ backups++ },{})
        updater.apply(SheetsSchema.LocaleCode.ES)
        updater.apply(SheetsSchema.LocaleCode.ES)
        assertEquals(1,backups)
        assertEquals(1,batches.size)
    }
    @Test fun `unverified backup or changed writer prevents style commit`() = runTest {
        for (failBackup in listOf(true,false)) {
            val api = mockk<Sheets>(relaxed=true)
            every { api.spreadsheets().get("same-book").setIncludeGridData(false).execute() } returns book()
            every { api.spreadsheets().get("same-book").setIncludeGridData(true).setFields(any()).execute() } returns book()
            val updater = SheetsAppearanceUpdater(api,"same-book",{ if (failBackup) error("SHEETS_BACKUP_UNVERIFIED") },{ error("SHEETS_OTHER_DEVICE") })
            assertTrue(runCatching { updater.apply(SheetsSchema.LocaleCode.ES) }.isFailure)
            val resource = api.spreadsheets()
            verify(exactly=0) { resource.batchUpdate(any(),any()) }
        }
    }

    @Test fun `reset timeout with unchanged old marker is not reported as applied`() = runTest {
        val api = mockk<Sheets>(relaxed=true)
        val source = mark(book())
        source.sheets[4].charts = listOf(EmbeddedChart().setChartId(41))
        every { api.spreadsheets().get("same-book").setIncludeGridData(false).execute() } returns source
        every { api.spreadsheets().get("same-book").setIncludeGridData(true).setFields(any()).execute() } returns source
        every { api.spreadsheets().batchUpdate("same-book",any()).execute() } throws java.net.SocketTimeoutException("not applied")
        assertTrue(runCatching { SheetsAppearanceUpdater(api,"same-book",{},{}).apply(SheetsSchema.LocaleCode.ES,true) }.isFailure)
    }
}
