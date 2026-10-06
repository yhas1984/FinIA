package com.gastos.di

import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.feature.ai.AIResult
import com.gastos.feature.ai.AIService
import com.gastos.feature.ai.GeminiRestClient
import com.gastos.local.database.AppDatabase
import com.gastos.repository.CurrencyPreference
import com.gastos.repository.impl.CountryFiscalConfigRepositoryImpl
import com.gastos.feature.settings.SecureStorage
import com.gastos.data.local.entity.ProductEntity
import com.gastos.storage.CommandCommit
import com.gastos.storage.CommandOperationStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Synthetic commands and an isolated database; never modify the user's ledger or API key. */
@RunWith(AndroidJUnit4::class)
class CommandProductPricingTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val response: String = """{"action":"add_expense","descripcion":"Synthetic coffee","total":2.60,
        "productos":[{"descripcion":"Café","cantidad":2,"precio_unitario":2.60,"subtotal":2.60}]}"""

    @Test fun reportedMessagesPersistCoherentProductsAndIdempotentReceipts(): Unit = runBlocking {
        val database: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val reader: AIService = reader(context, database)
            val operations: CommandOperationStore = CommandOperationStore(context, database)
            val messages: List<String> = listOf("agrega gasto 2 café por 2,60€",
                "agrega gasto por 2 cafés en 2,60€ ambos", "agrega gasto 2 cafés por 2,60€ ambos")
            messages.forEachIndexed { index: Int, message: String ->
                val parsed: AIResult = reader.parseStreamingResult(response, message)
                assertTrue(parsed.message, parsed.success)
                assertEquals(2.60, parsed.invoice!!.total, 0.0)
                assertEquals(1.30, parsed.products.single().precioUnitario, 0.0)
                assertNull(parsed.invoice!!.ivaPercent)
                assertNull(parsed.products.single().ivaPercent)
                val operation: String = "synthetic-coffee-$index"
                operations.begin(operation, message)
                val saved: CommandCommit = operations.commit(operation, parsed.invoice, null, parsed.products)
                val retry: CommandCommit = operations.commit(operation, parsed.invoice, null, parsed.products)
                assertEquals(saved.receipt.visibleText, retry.receipt.visibleText)
                val storedProducts: List<ProductEntity> = database.productDao().getProductsByInvoiceId(saved.invoice!!.id).first()
                assertEquals(1, storedProducts.size)
                assertEquals(1.30, storedProducts.single().precioUnitario, 0.0)
            }
            assertEquals(3, database.invoiceDao().getInvoiceCount())
            assertEquals(3, database.chatMessageDao().getAllMessages().count { it.role == "document" })
        } finally { database.close() }
    }

    @Test fun eachPricingAndLocalizedErrorsUseTheSameChatParser(): Unit = runBlocking {
        val database: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            for (locale: Locale in listOf(Locale.forLanguageTag("es-ES"), Locale.US)) {
                val localized: Context = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
                val reader: AIService = reader(localized, database)
                val each: AIResult = reader.parseStreamingResult(response, "agrega gasto 2 cafés a 2,60€ cada uno")
                assertTrue(each.message, each.success)
                assertEquals(5.20, each.invoice!!.total, 0.0)
                assertEquals(2.60, each.products.single().precioUnitario, 0.0)
                val invalid: AIResult = reader.parseStreamingResult(response, "agrega gasto 2,60€ en cafés")
                assertFalse(invalid.success)
                assertEquals(localized.getString(com.gastos.feature.ai.R.string.ai_invalid_command_products), invalid.message)
                assertFalse(invalid.message.contains("Inconsistent product"))
                assertNull(invalid.invoice)
                assertTrue(invalid.products.isEmpty())
            }
            assertEquals(0, database.invoiceDao().getInvoiceCount())
        } finally { database.close() }
    }

    @Test fun liveGeminiDistinguishesSharedTotalAndUnitPriceWithoutLedgerWrites(): Unit = runBlocking {
        assumeTrue("Requires explicit liveGemini opt-in", InstrumentationRegistry.getArguments().getString("liveGemini") == "true")
        val key: String = SecureStorage(context).getString(SecureStorage.KEY_GEMINI_API_KEY)
        assumeTrue("No Gemini key configured in FinAI Dev", key.isNotBlank())
        val database: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val reports: JSONArray = JSONArray()
        val report: File = File(context.getExternalFilesDir(null), "live-gemini-coffee-pricing.json")
        val reader: AIService = reader(context, database)
        try {
            reader.configureGemini(key, "")
            val cases: List<Pair<String, Double>> = listOf("agrega gasto 2 café por 2,60€" to 2.60,
                "agrega gasto por 2 cafés en 2,60€ ambos" to 2.60, "agrega gasto 2 cafés por 2,60€ ambos" to 2.60,
                "agrega gasto 2 cafés a 2,60€ cada uno" to 5.20)
            cases.forEachIndexed { index: Int, (message: String, expected: Double) ->
                reader.resetChat()
                val started: Long = SystemClock.elapsedRealtime()
                val parsed: AIResult = reader.processCommand(message)
                val row: JSONObject = JSONObject().put("case", index).put("durationMs", SystemClock.elapsedRealtime() - started)
                    .put("success", parsed.success).put("total", parsed.invoice?.total ?: JSONObject.NULL)
                    .put("quantity", parsed.products.sumOf { it.cantidad }).put("productsTotal", parsed.products.sumOf { it.subtotal })
                if (!parsed.success) row.put("error", parsed.message)
                reports.put(row)
                report.writeText(JSONObject().put("syntheticOnly", true).put("ledgerWrites", 0).put("cases", reports).toString(2))
                assertTrue("Synthetic case $index: ${parsed.message}", parsed.success)
                assertNotNull("Synthetic case $index has no expense", parsed.invoice)
                assertEquals("Synthetic case $index", expected, parsed.invoice!!.total, 0.000001)
                assertEquals("Synthetic case $index quantity", 2.0, parsed.products.sumOf { it.cantidad }, 0.000001)
                assertEquals("Synthetic case $index products", expected, parsed.products.sumOf { it.subtotal }, 0.000001)
                assertNull(parsed.invoice!!.ivaPercent)
                assertTrue(parsed.products.all { it.ivaPercent == null })
            }
            assertEquals(0, database.invoiceDao().getInvoiceCount())
        } finally {
            reader.configureGemini("", "")
            database.close()
        }
    }

    private fun reader(localized: Context, database: AppDatabase): AIService = AIService(localized,
        CountryFiscalConfigRepositoryImpl(database.countryFiscalConfigDao()), GeminiRestClient(),
        object : CurrencyPreference { override val defaultCurrency: MutableStateFlow<String> = MutableStateFlow("EUR") })
}
