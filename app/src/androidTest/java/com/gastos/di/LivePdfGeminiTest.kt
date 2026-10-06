package com.gastos.di

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.domain.model.TaxEffect
import com.gastos.feature.ai.*
import com.gastos.feature.settings.SecureStorage
import com.gastos.local.database.AppDatabase
import com.gastos.repository.CurrencyPreference
import com.gastos.repository.impl.CountryFiscalConfigRepositoryImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit opt-in: synthetic two-page PDF, no financial writes, no key in output. */
@RunWith(AndroidJUnit4::class)
class LivePdfGeminiTest {
    @Test fun readsOneMultipageInvoiceWithThreeTaxRates(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveGemini") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = SecureStorage(context).getString(SecureStorage.KEY_GEMINI_API_KEY)
        assumeTrue("No Gemini key configured in FinAI Dev", key.isNotBlank())
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val reader = AIService(context, CountryFiscalConfigRepositoryImpl(db.countryFiscalConfigDao()), GeminiRestClient(),
            object : CurrencyPreference { override val defaultCurrency = MutableStateFlow("EUR") })
        val source = File(context.cacheDir, "report_exports/qa-gemini-two-pages.pdf").apply { parentFile!!.mkdirs() }
        try {
            val pdf = PdfDocument()
            try {
                val pages = listOf(listOf("SYNTHETIC RECEIVED PURCHASE INVOICE - PAGE 1 OF 2", "Seller: Synthetic QA Store. Country: Spain ES",
                    "Invoice QA-PDF-001. Date: 2026-10-01. Currency: EUR", "Prices EXCLUDE tax. No discount or withholding.",
                    "Service A: quantity 1; unit net 100.00; line net 100.00; IVA 21%", "Service B: quantity 1; unit net 50.00; line net 50.00; IVA 10%",
                    "Service C: quantity 1; unit net 25.00; line net 25.00; IVA 4%"),
                    listOf("INVOICE QA-PDF-001 CONTINUED - PAGE 2 OF 2", "NET SUBTOTAL: 175.00 EUR", "IVA 21%: BASE 100.00 EUR; TAX 21.00 EUR",
                        "IVA 10%: BASE 50.00 EUR; TAX 5.00 EUR", "IVA 4%: BASE 25.00 EUR; TAX 1.00 EUR", "TOTAL TAX: 27.00 EUR", "TOTAL PAYABLE: 202.00 EUR"))
                pages.forEachIndexed { index, lines ->
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(900, 1200, index + 1).create())
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 19f }
                    lines.forEachIndexed { line, text -> page.canvas.drawText(text, 30f, 60f + line * 55f, paint) }
                    pdf.finishPage(page)
                }
                source.outputStream().use(pdf::writeTo)
            } finally { pdf.close() }
            reader.configureGemini(key, "")
            val start = SystemClock.elapsedRealtime()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
            val result = reader.readDocument(uri)
            val doc = when(result) { is DocumentReadResult.Ready -> result.evidence.document; is DocumentReadResult.NeedsReview -> result.evidence.document; else -> null }
            val charges = doc?.taxes?.filter { it.effect == TaxEffect.CHARGE }.orEmpty()
            val correct = doc?.total == 202.0 && doc.taxBase == 175.0 && doc.currency == "EUR" && doc.vatPercent == null &&
                charges.map { it.rate }.sortedBy { it } == listOf(4.0, 10.0, 21.0) && charges.map { it.amount }.sortedBy { it } == listOf(1.0, 5.0, 21.0)
            File(context.filesDir, "live-pdf-gemini-result.json").writeText(JSONObject().put("syntheticOnly", true).put("ledgerWrites", 0)
                .put("durationMs", SystemClock.elapsedRealtime() - start).put("result", result.javaClass.simpleName).put("printedValuesPreserved", correct)
                .put("error", (result as? DocumentReadResult.Failure)?.message ?: JSONObject.NULL).toString(2))
            assertTrue("Synthetic PDF extraction failed; see the private device report", correct && result is DocumentReadResult.Ready)
        } finally { reader.configureGemini("", ""); source.delete(); db.close() }
    }
}
