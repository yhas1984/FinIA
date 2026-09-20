package com.gastos.di

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.MainActivity
import com.gastos.data.local.entity.toEntity
import com.gastos.domain.model.*
import com.gastos.feature.invoices.EditInvoiceScreen
import com.gastos.feature.incomes.EditIncomeScreen
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class DocumentTaxUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun expenseMixedTaxesSpanish() = verifyEditor("es", false)
    @Test fun incomeSharedTaxesEnglish() = verifyEditor("en", true)

    @Suppress("DEPRECATION")
    private fun verifyEditor(language: String, isIncome: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val originalLocale = Locale.getDefault()
        val originalConfiguration = Configuration(context.resources.configuration)
        val database = AppModule.provideDatabase(context)
        val taxes = if (isIncome) listOf(DocumentTax("GST", 5.0, 100.0, 5.0, TaxTreatment.TAXABLE), DocumentTax("PST", 7.0, 100.0, 7.0, TaxTreatment.TAXABLE))
            else listOf(DocumentTax("IVA", 21.0, 100.0, 21.0, TaxTreatment.TAXABLE), DocumentTax("IVA", 10.0, 50.0, 5.0, TaxTreatment.TAXABLE), DocumentTax("IVA", 4.0, 25.0, 1.0, TaxTreatment.TAXABLE))
        val invoice = Invoice(documentUuid = "synthetic-tax-ui-$language", fecha = DocumentValidator.parseDate("2026-09-20")!!,
            proveedor = "SYNTHETIC TAX DOCUMENT", tipo = InvoiceType.GASTO, total = if (isIncome) 112.0 else 202.0,
            baseImponible = if (isIncome) 100.0 else 175.0, cuotaIva = if (isIncome) 12.0 else 27.0,
            moneda = if (isIncome) "CAD" else "EUR", ivaPercent = null, taxes = taxes)
        val id = runBlocking {
            if (isIncome) database.incomeDao().insertIncomeEntity(invoice.toIncome().toEntity()) else database.invoiceDao().insertInvoice(invoice.toEntity())
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    Locale.setDefault(Locale(language))
                    val configuration = Configuration(activity.resources.configuration).apply { setLocale(Locale(language)) }
                    activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
                    activity.setContent { MaterialTheme {
                        if (isIncome) EditIncomeScreen(id, {}) else EditInvoiceScreen(id, {})
                    } }
                }
                val title = if (language == "es") "Impuestos (3)" else "Taxes (2)"
                compose.waitUntil(15_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(title).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(title).performClick()
                val name = if (isIncome) "GST" else "IVA"
                compose.onAllNodesWithText(name).onFirst().performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(if (language == "es") "Revisar documento" else "Review document").assertDoesNotExist()
                compose.onAllNodesWithText(if (language == "es") "IVA %" else "VAT %").assertCountEquals(0)
                compose.onAllNodesWithText(if (language == "es") "Quitar impuesto" else "Remove tax").onFirst().performScrollTo().assertIsDisplayed()
                compose.onAllNodesWithText(if (isIncome) "5" else "21").onFirst().assertIsDisplayed()
                compose.waitForIdle()
                instrumentation.uiAutomation.waitForIdle(500, 5_000)
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                File(context.getExternalFilesDir(null), "tax-editor-$language.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
            }
        } finally {
            runBlocking {
                if (isIncome) database.incomeDao().deleteByIdentity(id, invoice.documentUuid)
                else database.invoiceDao().deleteByIdentity(id, invoice.documentUuid)
            }
            database.close()
            Locale.setDefault(originalLocale)
            context.resources.updateConfiguration(originalConfiguration, context.resources.displayMetrics)
        }
    }
}
