package com.gastos.di

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.domain.model.TaxEffect
import com.gastos.feature.ai.AIService
import com.gastos.feature.ai.DocumentReadResult
import com.gastos.feature.ai.GeminiRestClient
import com.gastos.feature.settings.SecureStorage
import com.gastos.local.database.AppDatabase
import com.gastos.repository.CurrencyPreference
import com.gastos.repository.impl.CountryFiscalConfigRepositoryImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit opt-in only. The key stays on the device; synthetic reads never enter the ledger. */
@RunWith(AndroidJUnit4::class)
class LiveGeminiSmokeTest {
    @Test fun readSyntheticMixedTaxDocuments(): Unit = runBlocking {
        assumeTrue("Requires -e liveGemini true", InstrumentationRegistry.getArguments().getString("liveGemini") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = File(context.getExternalFilesDir(null), "live-gemini-taxes.json")
        val key: String = SecureStorage(context).getString(SecureStorage.KEY_GEMINI_API_KEY)
        report.writeText(JSONObject().put("keyConfigured", key.isNotBlank()).put("syntheticOnly", true).toString(2))
        assumeTrue("No Gemini key configured in FinAI Dev", key.isNotBlank())
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val reader = AIService(context, CountryFiscalConfigRepositoryImpl(database.countryFiscalConfigDao()),
            GeminiRestClient(), object : CurrencyPreference { override val defaultCurrency = MutableStateFlow("EUR") })
        val reports = JSONArray()
        try {
            reader.configureGemini(key, "")
            for (fixture in fixtures) {
                val file = File.createTempFile("synthetic-tax-", ".png", context.cacheDir)
                try {
                    val bitmap = Bitmap.createBitmap(1500, 1700, Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).apply {
                        drawColor(Color.WHITE)
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 36f }
                        fixture.lines.forEachIndexed { index, line -> drawText(line, 60f, 90f + index * 65f, paint) }
                    }
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    val started: Long = SystemClock.elapsedRealtime()
                    val result: DocumentReadResult = reader.readDocument(Uri.fromFile(file))
                    val row = JSONObject().put("fixture", fixture.id).put("durationMs", SystemClock.elapsedRealtime() - started)
                        .put("result", result.javaClass.simpleName)
                    val document = when (result) {
                        is DocumentReadResult.Ready -> result.evidence.document
                        is DocumentReadResult.NeedsReview -> result.evidence.document.also {
                            row.put("issues", JSONArray(result.issues.map { issue -> "${issue.field}:${issue.reason}" }))
                        }
                        else -> null
                    }
                    if (result is DocumentReadResult.Failure) row.put("error", result.message)
                    val charges = document?.taxes?.filter { it.effect == TaxEffect.CHARGE }.orEmpty()
                    row.put("documentKind", document?.kind ?: JSONObject.NULL)
                    val correct: Boolean = document != null && document.total == fixture.total &&
                        document.taxBase == fixture.base && document.currency == fixture.currency && document.vatPercent == null &&
                        document.lines.map { it.subtotal }.sortedBy { it } == fixture.subtotals.sorted() &&
                        document.lines.all { it.quantity == 1.0 } &&
                        charges.map { it.rate }.sortedBy { it } == fixture.rates.sorted() &&
                        charges.map { it.amount }.sortedBy { it } == fixture.quotas.sorted() &&
                        charges.map { it.base }.sortedBy { it } == fixture.bases.sorted()
                    row.put("printedValuesPreserved", correct)
                    row.put("readyToSave", result is DocumentReadResult.Ready)
                    reports.put(row)
                    report.writeText(JSONObject().put("keyConfigured", true).put("syntheticOnly", true)
                        .put("ledgerWrites", 0).put("reads", reports).toString(2))
                } finally { file.delete() }
            }
            assertTrue("Synthetic live OCR did not preserve every required value; see the device report",
                (0 until reports.length()).all { reports.getJSONObject(it).getBoolean("printedValuesPreserved") &&
                    reports.getJSONObject(it).getBoolean("readyToSave") })
        } finally {
            reader.configureGemini("", "")
            database.close()
        }
    }

    private data class Fixture(val id: String, val currency: String, val base: Double, val total: Double,
        val subtotals: List<Double>, val rates: List<Double>, val bases: List<Double>, val quotas: List<Double>, val lines: List<String>)

    private val fixtures: List<Fixture> = listOf(
        Fixture("es-three-rates", "EUR", 175.0, 202.0, listOf(100.0, 50.0, 25.0), listOf(21.0, 10.0, 4.0),
            listOf(100.0, 50.0, 25.0), listOf(21.0, 5.0, 1.0), listOf(
                "SYNTHETIC TEST - RECEIVED PURCHASE INVOICE", "FACTURA RECIBIDA - COPIA DEL COMPRADOR", "Seller: Synthetic Test Store", "Country: Spain (ES)",
                "Invoice: TEST-ES-001    Date: 2026-09-20", "Currency: EUR    Prices EXCLUDE tax",
                "ITEM             QTY       UNIT NET       LINE NET      IVA",
                "Service A        1           100.00           100.00         21%",
                "Service B        1             50.00             50.00         10%",
                "Service C        1             25.00             25.00           4%",
                "NET SUBTOTAL: 175.00 EUR", "IVA 21%: BASE 100.00 EUR    TAX 21.00 EUR",
                "IVA 10%: BASE   50.00 EUR    TAX   5.00 EUR", "IVA   4%: BASE   25.00 EUR    TAX   1.00 EUR",
                "TOTAL TAX: 27.00 EUR", "TOTAL PAYABLE: 202.00 EUR", "No discount. No withholding.")),
        Fixture("ca-shared-base", "CAD", 100.0, 112.0, listOf(100.0), listOf(5.0, 7.0), listOf(100.0, 100.0),
            listOf(5.0, 7.0), listOf(
                "SYNTHETIC TEST - RECEIVED PURCHASE INVOICE", "Document type: received invoice (buyer's copy)", "Seller: Synthetic Test Store", "Country: Canada (CA)",
                "Invoice: TEST-CA-001    Date: 2026-09-20", "Currency: CAD    Prices EXCLUDE tax",
                "ITEM             QTY       UNIT NET       LINE NET", "Service A        1           100.00           100.00",
                "NET SUBTOTAL: 100.00 CAD", "GST 5%: BASE 100.00 CAD    TAX 5.00 CAD",
                "PST 7%: BASE 100.00 CAD    TAX 7.00 CAD", "TOTAL TAX: 12.00 CAD",
                "TOTAL PAYABLE: 112.00 CAD", "No discount. No withholding."))
    )
}
