package com.gastos.di

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.common.TaxBreakdownSummary
import com.gastos.common.describeTax
import com.gastos.domain.model.DocumentTax
import com.gastos.domain.model.TaxTreatment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class TaxPresentationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun spanishSummaryAndPdfTextShowEachRateOnce() = checkPresentation("es-ES")
    @Test fun englishSummaryAndPdfTextShowEachRateOnce() = checkPresentation("en-US")

    private fun checkPresentation(language: String) {
        val base: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration: Configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        val context: Context = base.createConfigurationContext(configuration)
        val taxes: List<DocumentTax> = listOf(21.0, 10.0, 4.0).map { rate ->
            DocumentTax("IVA ${rate.toInt()} %", rate, 100.0, rate, TaxTreatment.TAXABLE)
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration) {
                MaterialTheme { TaxBreakdownSummary(taxes, "EUR") }
            }
        }
        compose.onNodeWithText(context.getString(com.gastos.common.R.string.taxes_breakdown_count, 3)).performClick()
        taxes.forEach { tax ->
            compose.onNodeWithText("${tax.name} ·", substring = true).assertIsDisplayed()
            val pdfText: String = describeTax(context, tax, "EUR")
            assertEquals(1, pdfText.count { it == '%' })
            assertFalse(pdfText.contains("${tax.rate} %"))
        }
    }
}
