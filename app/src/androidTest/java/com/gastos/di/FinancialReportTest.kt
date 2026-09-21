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
