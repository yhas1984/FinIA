package com.gastos.feature.backup

import com.gastos.domain.model.*
import com.gastos.repository.ExchangeRateProvider
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SheetsWorkbookEngineTest {
    private val api = mockk<Sheets>(relaxed = true)
    private val rates = mockk<ExchangeRateProvider>(relaxed = true)
    private val conversion = SheetsSchema.ConversionSnapshot("EUR", SheetsSchema.LocaleCode.ES, rates)
    private val descriptor = SheetsSchema.descriptor(SheetsSchema.LocaleCode.ES)
    private val batches = mutableListOf<BatchUpdateSpreadsheetRequest>()
    private fun prepare(rows: List<List<Any>> = emptyList()) {
        val sheets = listOf(descriptor.recibidasTitle, descriptor.ingresosTitle, descriptor.productosTitle, "Impuestos", descriptor.resumenTitle, "Análisis personal").mapIndexed { index, title ->
            Sheet().setProperties(SheetProperties().setSheetId(index).setTitle(title).setGridProperties(GridProperties().setRowCount(1000).setColumnCount(26)))
                .setBasicFilter(BasicFilter().setRange(GridRange().setSheetId(index)).setCriteria(mapOf("1" to FilterCriteria().setHiddenValues(listOf("private filter")))))
        }
        every { api.spreadsheets().get("book").setIncludeGridData(false).execute() } returns Spreadsheet().setSheets(sheets)
        every { api.spreadsheets().values().batchGet("book").setRanges(any()).setValueRenderOption("UNFORMATTED_VALUE").setDateTimeRenderOption("SERIAL_NUMBER").execute() } returns
            BatchGetValuesResponse().setValueRanges(listOf(ValueRange().setValues(rows), ValueRange(),ValueRange(),ValueRange()))
        every { api.spreadsheets().batchUpdate("book",capture(batches)).execute() } returns BatchUpdateSpreadsheetResponse()
    }
    @Test fun `document products taxes and remote summary use one commit and preserve filters`() = runTest {
        prepare()
        val invoice = Invoice(id=1,tipo=InvoiceType.GASTO,fecha=1_700_000_000_000,proveedor="Store",total=12.1,moneda="EUR",ivaPercent=null,
            taxes=listOf(DocumentTax("VAT",21.0,10.0,2.1,TaxTreatment.TAXABLE)))
        val product = Product(id=1,invoiceId=1,descripcion="Item",cantidad=1.0,precioUnitario=10.0,subtotal=10.0)
        SheetsWorkbookEngine(api,"book",conversion).write(listOf(invoice),emptyList(),listOf(product))
        assertEquals(1,batches.size)
        val requests = batches.single().requests
        assertTrue(requests.none { it.deleteSheet != null || it.deleteDimension != null || it.setBasicFilter != null })
        assertTrue(requests.mapNotNull { it.updateCells?.start?.sheetId }.containsAll(listOf(0,2,3,4)))
        val formulas = requests.flatMap { it.updateCells?.rows.orEmpty() }.flatMap { it.getValues() }.mapNotNull { it.userEnteredValue?.formulaValue }
        assertTrue(formulas.any { it.contains("SUMPRODUCT") && it.contains("'Facturas Recibidas'!") })
        assertTrue(formulas.none { it.contains("12.1") }) // A local amount is never a summary literal.
        assertTrue(formulas.any { it.contains("No disponible") && it.contains("=B7") })
    }
    @Test fun `applied timeout retry updates document at existing row without duplicate append`() = runTest {
        val invoice = Invoice(id=1,tipo=InvoiceType.GASTO,fecha=1_700_000_000_000,proveedor="Store",total=12.1,moneda="EUR",ivaPercent=null)
        prepare(listOf(descriptor.recibidasHeaders, SheetsSchema.expenseRow(invoice,conversion)))
        SheetsWorkbookEngine(api,"book",conversion).write(listOf(invoice),emptyList(),emptyList())
        assertTrue(batches.single().requests.filter { it.updateCells?.start?.sheetId == 0 }.all { it.updateCells.start.rowIndex <= 1 })
    }
    @Test fun `writer transfer before commit prevents any financial write`() = runTest {
        prepare()
        val result = runCatching { SheetsWorkbookEngine(api,"book",conversion) { error("SHEETS_OTHER_DEVICE") }.write(emptyList(),emptyList(),emptyList()) }
        assertTrue(result.isFailure)
        assertTrue(batches.isEmpty())
    }
}
