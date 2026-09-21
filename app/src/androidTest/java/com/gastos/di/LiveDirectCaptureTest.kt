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
import com.gastos.domain.model.documentIdentity
import com.gastos.feature.ai.AIService
import com.gastos.feature.ai.DocumentReadResult
import com.gastos.feature.ai.GeminiRestClient
import com.gastos.feature.settings.SecureStorage
import com.gastos.local.database.AppDatabase
import com.gastos.repository.CurrencyPreference
import com.gastos.repository.impl.CountryFiscalConfigRepositoryImpl
import com.gastos.storage.CaptureSave
import com.gastos.storage.CaptureStart
import com.gastos.storage.DocumentCaptureStore
import com.gastos.storage.InvoiceImageStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in real OCR with synthetic documents and an isolated ledger. No Drive/Sheets writes. */
@RunWith(AndroidJUnit4::class)
class LiveDirectCaptureTest {
    @Test fun receiptsInvoicesAndPayrollSaveDirectly(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveGemini") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".dev"))
        val key: String = SecureStorage(context).getString(SecureStorage.KEY_GEMINI_API_KEY)
        assumeTrue("Gemini key required on the test device", key.isNotBlank())
        val database: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val images: InvoiceImageStorage = InvoiceImageStorage(context)
        val store: DocumentCaptureStore = DocumentCaptureStore(context, database, images)
        val reader: AIService = AIService(context, CountryFiscalConfigRepositoryImpl(database.countryFiscalConfigDao()), GeminiRestClient(),
            object : CurrencyPreference { override val defaultCurrency: MutableStateFlow<String> = MutableStateFlow("EUR") })
        val report: JSONArray = JSONArray()
        val reportFile: File = File(context.filesDir, "direct-capture-live.json")
        val savedPhotos: MutableList<String> = mutableListOf()
        val captures: MutableList<String> = mutableListOf()
        try {
            reader.configureGemini(key, "")
            for (fixture: Fixture in fixtures) {
                val source: File = File.createTempFile("direct-capture-", ".png", context.cacheDir)
                try {
                    val bitmap: Bitmap = Bitmap.createBitmap(1500, 1500, Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).apply {
                        drawColor(Color.WHITE)
                        val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 36f }
                        fixture.lines.forEachIndexed { index, line -> drawText(line, 50f, 80f + index * 65f, paint) }
                    }
                    source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    val started: Long = SystemClock.elapsedRealtime()
                    val draft: CaptureStart.Draft = store.start(Uri.fromFile(source)) as CaptureStart.Draft
                    captures.add(draft.value.uuid)
                    val result: DocumentReadResult = reader.readDocument(Uri.parse(draft.value.imageUri))
                    val row: JSONObject = JSONObject().put("fixture", fixture.id).put("readMs", SystemClock.elapsedRealtime() - started)
                        .put("result", result.javaClass.simpleName)
                    report.put(row)
                    reportFile.writeText(report.toString(2))
                    assertTrue("Expected direct extraction for ${fixture.id}", result is DocumentReadResult.Ready)
                    val evidence = (result as DocumentReadResult.Ready).evidence
                    store.update(draft.value.uuid, evidence)
                    val saved: CaptureSave = store.save(draft.value.uuid, evidence)
                    assertTrue("Expected direct save for ${fixture.id}", saved is CaptureSave.Saved)
                    saved as CaptureSave.Saved
                    (saved.invoice?.imagenUri ?: saved.income?.imagenUri)?.let(savedPhotos::add)
                    val identity = requireNotNull(saved.invoice?.documentIdentity() ?: saved.income?.documentIdentity())
                    assertEquals(fixture.amount, identity.amount, 0.001)
                    assertEquals(fixture.income, identity.isIncome)
                    assertEquals(1, database.chatMessageDao().getAllMessages().count { it.documentUuid == identity.uuid })
                    assertNull(store.get(identity.uuid))
                    assertTrue(store.start(Uri.fromFile(source)) is CaptureStart.Duplicate)
                    row.put("saved", true).put("printedAmountPreserved", true).put("duplicateBlocked", true)
                    reportFile.writeText(report.toString(2))
                } finally { source.delete() }
            }
        } finally {
            captures.forEach { store.discard(it) }
            savedPhotos.forEach { images.delete(it) }
            database.close()
            reader.configureGemini("", "")
        }
    }

    private data class Fixture(val id: String, val amount: Double, val income: Boolean, val lines: List<String>)
    private val fixtures: List<Fixture> = listOf(
        Fixture("simple-receipt", 12.50, false, listOf("SYNTHETIC TEST RECEIPT", "CAFE DEMO", "21/09/2026",
            "Coffee and breakfast", "TOTAL PAID: 12.50 EUR", "Thank you")),
        Fixture("three-vat-invoice", 202.0, false, listOf("SYNTHETIC PURCHASE INVOICE - BUYER COPY", "Supplier: Demo Store", "Invoice DIRECT-001, Date 21/09/2026",
            "Currency EUR, prices before tax", "Service A: qty 1, price 100.00, subtotal 100.00, VAT 21%",
            "Service B: qty 1, price 50.00, subtotal 50.00, VAT 10%", "Service C: qty 1, price 25.00, subtotal 25.00, VAT 4%",
            "NET SUBTOTAL 175.00", "IVA 21%: base 100.00, tax 21.00", "IVA 10%: base 50.00, tax 5.00",
            "IVA 4%: base 25.00, tax 1.00", "TOTAL TAX 27.00", "TOTAL TO PAY 202.00 EUR")),
        Fixture("partial-payroll", 2052.0, true, listOf("NOMINA - EJEMPLO FICTICIO", "Empresa: Empresa Demo SL", "Trabajador: Persona Demo",
            "Periodo: del 1 al 31 de enero de 2026", "Moneda EUR", "TOTAL DEVENGADO: 2400.00 EUR",
            "TOTAL DEDUCCIONES: 348.00 EUR", "LIQUIDO A PERCIBIR: 2052.00 EUR")))
}
