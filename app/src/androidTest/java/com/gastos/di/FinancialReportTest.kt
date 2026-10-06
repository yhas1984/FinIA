package com.gastos.di

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.domain.model.*
import com.gastos.feature.backup.FinancialReportWriter
import com.gastos.feature.backup.ReportFormat
import com.gastos.feature.backup.ReportDetail
import com.gastos.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FinancialReportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val currency = object : CurrencyPreference { override val defaultCurrency = MutableStateFlow("EUR") }
    private val rates = object : ExchangeRateProvider {
        override val rates = MutableStateFlow(mapOf("EUR" to 0.9, "USD" to 1.0))
        override val lastUpdated = MutableStateFlow<Long?>(1L)
        override suspend fun refresh() = Unit
        override fun convert(amount: Double, from: String, to: String): Double? =
            if (from == to) amount else rates.value[from]?.let { source -> rates.value[to]?.let { amount * it / source } }
    }
    private class Repository(val dataset: BackupDataset) : BackupDataRepository {
        override suspend fun snapshot(): BackupDataset { assertNotSame(Looper.getMainLooper(),Looper.myLooper()); return dataset }
        override suspend fun replaceAll(dataset: BackupDataset) = Unit
        override suspend fun replaceAllWithRestoreMarker(dataset: BackupDataset, restoreId: String) = Unit
        override suspend fun committedRestoreId(): String? = null
        override suspend fun clearRestoreMarker(restoreId: String) = Unit
    }
    private fun dataset(size: Int = 1): BackupDataset {
        val expenses = (1..size).map { id -> Invoice(id=id.toLong(),fecha=1_700_000_000_000,proveedor="Long description $id " + "document detail ".repeat(20),tipo=InvoiceType.GASTO,total=100.0,moneda="EUR",ivaPercent=null) }
        val legacy = Invoice(id=10_001,fecha=1_700_000_000_000,proveedor="LEGACY-INCOME-ONCE",tipo=InvoiceType.INGRESO,total=250.0,moneda="EUR",ivaPercent=null)
        return BackupDataset(expenses + legacy, listOf(Product(id=1,invoiceId=1,descripcion="PRODUCT-REFERENCE",cantidad=1.0,precioUnitario=100.0,subtotal=100.0)),emptyList(),emptyList(),emptyList())
    }
    @Test fun csvContainsParentIdentityAndDateAndLegacyIncomeExactlyOnce() = runBlocking<Unit> {
        val data = dataset(500)
        val writer = FinancialReportWriter(context,Repository(data),currency,rates)
        val file = writer.generate(ReportFormat.CSV) { assertNotSame(Looper.getMainLooper(),Looper.myLooper()) }
        val csv = file.readText()
        assertEquals(1,Regex("LEGACY-INCOME-ONCE").findAll(csv).count())
        val product = csv.lines().single { it.contains("PRODUCT-REFERENCE") }
        assertTrue(product.contains(data.invoices.first().documentUuid))
        assertTrue(product.contains("2023-11-14"))
        assertTrue(csv.contains("parent_document_uuid"))
        file.delete()
    }
    @Test fun filteredReportExcludesOtherMovementsAndTheirProducts(): Unit = runBlocking {
        val included = Invoice(id=1,fecha=100,proveedor="INCLUDED-EXPENSE",tipo=InvoiceType.GASTO,total=20.0,moneda="EUR",categoria="Forza",subcategoria="Gasolina",ivaPercent=null)
        val excluded = included.copy(id=2,documentUuid="excluded",fecha=300,proveedor="EXCLUDED-EXPENSE")
        val income = Income(fecha=100,concepto="EXCLUDED-INCOME",monto=99.0,categoria="Forza",subcategoria="Gasolina")
        val data = BackupDataset(listOf(included,excluded), listOf(Product(invoiceId=1,descripcion="INCLUDED-PRODUCT",precioUnitario=20.0), Product(invoiceId=2,descripcion="EXCLUDED-PRODUCT",precioUnitario=20.0)), listOf(income),emptyList(),emptyList())
        val writer = FinancialReportWriter(context, Repository(data),currency,rates)
        val file = writer.generate(ReportFormat.CSV, ReportFilter(0,200,DocumentKind.EXPENSE,"Forza","Gasolina")) {}
        try {
            val csv=file.readText()
            assertTrue(csv.contains("INCLUDED-EXPENSE"))
            assertTrue(csv.contains("INCLUDED-PRODUCT"))
            assertFalse(csv.contains("EXCLUDED-EXPENSE"))
            assertFalse(csv.contains("EXCLUDED-PRODUCT"))
            assertFalse(csv.contains("EXCLUDED-INCOME"))
        } finally { file.delete() }
    }
    @Test fun longPdfWrapsAndPaginatesAndCanBeRendered() = runBlocking<Unit> {
        val writer = FinancialReportWriter(context,Repository(dataset(100)),currency,rates)
        val file = writer.generate(ReportFormat.PDF) {}
        ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertTrue(renderer.pageCount > 5)
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width*2,page.height*2,Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    File(context.filesDir,"report-page.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                }
            }
        }
        file.delete()
    }
    @Test fun detailedSyntheticMixedTaxesAndPayrollRendersEveryPage(): Unit = runBlocking(Dispatchers.IO) {
        val invoiceDate = requireNotNull(DocumentValidator.parseDate("2026-09-15"))
        val payrollDate = requireNotNull(DocumentValidator.parseDate("2026-09-30"))
        val taxes = listOf(
            DocumentTax("IVA",21.0,100.0,21.0,TaxTreatment.TAXABLE),
            DocumentTax("IVA",10.0,50.0,5.0,TaxTreatment.TAXABLE),
            DocumentTax("IVA",4.0,25.0,1.0,TaxTreatment.TAXABLE)
        )
        val expense = Invoice(id=101,documentUuid="550e8400-e29b-41d4-a716-446655440101",fecha=invoiceDate,
            proveedor="COMERCIO SINTÉTICO · prueba de informe detallado",tipo=InvoiceType.GASTO,
            total=202.0,moneda="EUR",ivaPercent=null,baseImponible=175.0,cuotaIva=27.0,taxes=taxes,
            numeroFactura="TEST-PDF-MIXED-TAX-2026",categoria="Compras de prueba",subcategoria="Varios impuestos",
            notas="Documento ficticio para validación visual. No corresponde a una compra ni a una persona real.")
        val descriptions = listOf("QA-PRODUCT-21", "QA-PRODUCT-10", "QA-PRODUCT-4")
        val amounts = listOf(121.0,55.0,26.0)
        val products = taxes.mapIndexed { index, tax ->
            Product(id=101L+index,invoiceId=expense.id,cantidad=1.0,precioUnitario=amounts[index],subtotal=amounts[index],
                ivaPercent=tax.rate,taxes=listOf(tax),descripcion=descriptions[index]+" · "+
                    ("Descripción sintética extensa para comprobar saltos de línea, palabras acentuadas y lectura de detalles del producto. ").repeat(8))
        }
        val payroll = PayrollDetails(periodStart="2026-09-01",periodEnd="2026-09-30",paymentDate="2026-09-30",
            totalDeductions=400.0,linesComplete=true,lines=listOf(
                PayrollLine("QA-SALARIO-BASE",PayrollLineType.EARNING,1800.0),
                PayrollLine("QA-COMPLEMENTO",PayrollLineType.EARNING,200.0),
                PayrollLine("QA-SEGURIDAD-SOCIAL",PayrollLineType.DEDUCTION,130.0,deductionType=PayrollDeductionType.SOCIAL_SECURITY),
                PayrollLine("QA-IRPF",PayrollLineType.DEDUCTION,270.0,deductionType=PayrollDeductionType.INCOME_TAX)
            ))
        val income = Income(id=201,documentUuid="550e8400-e29b-41d4-a716-446655440201",fecha=payrollDate,
            concepto="NÓMINA SINTÉTICA · septiembre de 2026",monto=1600.0,totalDevengado=2000.0,totalNeto=1600.0,
            moneda="EUR",ivaPercent=null,irpfPercent=13.5,categoria="Trabajo",subcategoria="Nómina",
            notas="Empresa y trabajador ficticios. Importe bruto 2.000 EUR, deducciones 400 EUR y neto 1.600 EUR.",
            evidence=DocumentEvidence(ScannedDocument(kind="nomina",country="ES",currency="EUR",date="2026-09-30",
                number="TEST-PDF-PAYROLL-2026",issuer="EMPRESA SINTÉTICA",gross=2000.0,net=1600.0,
                socialSecurity=130.0,withholdingAmount=270.0,withholdingPercent=13.5,payroll=payroll)))
        val data = BackupDataset(listOf(expense),products,listOf(income),emptyList(),emptyList())
        val writer = FinancialReportWriter(context,Repository(data),currency,rates)
        var progress = 0f
        val generated = writer.generateReport(ReportFormat.PDF,ReportFilter(),ReportDetail.DETAILED) { next ->
            assertNotSame(Looper.getMainLooper(),Looper.myLooper())
            assertTrue("Progress cannot go backwards",next >= progress)
            progress = next
        }
        val retained = File(context.filesDir,"consolidation-report-detailed.pdf")
        val report = org.json.JSONObject().put("syntheticOnly",true).put("textExtracted",false).put("passed",false)
            .put("expectedExpense",202.0).put("expectedIncomeNet",1600.0).put("expectedBalance",1398.0)
            .put("expectedTaxRates",org.json.JSONArray(listOf(21,10,4)))
            .put("expectedPayrollGross",2000.0).put("expectedPayrollDeductions",400.0)
            .put("expectedPayrollNet",1600.0)
        try {
            assertEquals(1f,progress)
            generated.copyTo(retained,overwrite=true)
            assertTrue(retained.length() > 0L)
            ParcelFileDescriptor.open(retained,ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    assertTrue("Long product descriptions must paginate",renderer.pageCount >= 2)
                    report.put("pageCount",renderer.pageCount)
                    for (index in 0 until renderer.pageCount) {
                        renderer.openPage(index).use { page ->
                            val bitmap = Bitmap.createBitmap(page.width*2,page.height*2,Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(android.graphics.Color.WHITE)
                                page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val pixels = IntArray(bitmap.width*bitmap.height)
                                bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                                assertTrue("Rendered page ${index+1} must contain visible content",
                                    pixels.count { it != android.graphics.Color.WHITE } > 100)
                                val suffix = when (index) {
                                    0 -> "first"
                                    renderer.pageCount-1 -> "last"
                                    else -> null
                                }
                                if (suffix != null) File(context.filesDir,"consolidation-report-detailed-$suffix.png").outputStream().use {
                                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))
                                }
                            } finally { bitmap.recycle() }
                        }
                    }
                }
            }
            // PdfRenderer verifies readable pages and visible pixels, not the meaning of PDF text.
            report.put("passed",true).put("everyPageRendered",true).put("bytes",retained.length())
        } finally {
            File(context.filesDir,"consolidation-report-detailed.json").writeText(report.toString(2))
            generated.delete()
        }
    }
    @Test fun cancellationRemovesPartialOutputAndExpiryOnlyRemovesOldTemporaryReports() = runBlocking<Unit> {
        val folder = File(context.cacheDir,"report_exports").apply { mkdirs() }
        val old = File(folder,"expired-report.csv").apply { writeText("temporary"); setLastModified(System.currentTimeMillis()-8L*24*60*60*1000) }
        val recent = File(folder,"recent-report.csv").apply { writeText("keep") }
        val saved = File(context.filesDir,"user_saved_report.csv").apply { writeText("user-owned"); setLastModified(old.lastModified()) }
        val before = folder.listFiles().orEmpty().map { it.name }.toSet()
        val writer = FinancialReportWriter(context,Repository(dataset(500)),currency,rates)
        val job = launch { writer.generate(ReportFormat.CSV) { value -> if (value > 0.2f) throw CancellationException("synthetic cancellation") } }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(old.exists())
        assertTrue(recent.exists())
        assertTrue(saved.exists())
        assertTrue(folder.listFiles().orEmpty().all { it.name in before })
        recent.delete(); saved.delete()
    }
}
