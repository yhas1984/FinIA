package com.gastos.di

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.FinAIApp
import com.gastos.MainActivity
import com.gastos.ui.theme.GastosEIngresosTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class ManualCompactUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)
    private fun field(id: Int) = compose.onNode(hasText(text(id)) and hasSetTextAction())
    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(context.filesDir,"manual-sheets-ui/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    private fun manual(income: Boolean) {
        compose.onNodeWithContentDescription(text(com.gastos.R.string.open_ai_assistant)).performClick()
        compose.onNodeWithText(text(com.gastos.feature.chatbot.R.string.chatbot_scan)).performClick()
        compose.onNodeWithText(text(if (income) com.gastos.feature.chatbot.R.string.manual_income else com.gastos.feature.chatbot.R.string.manual_expense)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun expenseUsesOneKeyboardSafeSaveAndRevealsInvalidTaxWithoutLosingDraft() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            manual(false)
            compose.onNodeWithText(text(com.gastos.common.R.string.manual_save_expense)).assertIsDisplayed()
            compose.onNodeWithText(text(com.gastos.feature.invoices.R.string.validation_provider_required)).assertDoesNotExist()
            compose.onNodeWithText("0 %").assertExists()
            shot("expense-new")
            compose.onNodeWithText(text(com.gastos.common.R.string.manual_save_expense)).performClick()
            field(com.gastos.feature.invoices.R.string.total).assertIsFocused()
            field(com.gastos.feature.invoices.R.string.total).performTextInput("12,50")
            field(com.gastos.feature.invoices.R.string.provider).performTextInput("Comercio con un nombre muy largo para comprobar la edición manual")
            compose.onNodeWithText(text(com.gastos.common.R.string.essential_taxes)).performScrollTo().performClick()
            field(com.gastos.feature.invoices.R.string.vat_percent).performScrollTo().performTextReplacement("150")
            compose.onNodeWithText(text(com.gastos.common.R.string.manual_save_expense)).assertIsDisplayed().performClick()
            field(com.gastos.feature.invoices.R.string.vat_percent).assertIsFocused()
            compose.onNodeWithText(text(com.gastos.feature.invoices.R.string.validation_total_percentages)).assertIsDisplayed()
            shot("expense-tax-error-keyboard")
            scenario.recreate()
            field(com.gastos.feature.invoices.R.string.vat_percent).performScrollTo().assertTextContains("150")
            field(com.gastos.feature.invoices.R.string.total).performScrollTo().assertTextContains("12,50")
            shot("expense-recovered")
            compose.onNodeWithContentDescription(text(com.gastos.common.R.string.essential_back)).performClick()
            compose.onNodeWithText(text(com.gastos.common.R.string.essential_keep_editing)).performClick()
            field(com.gastos.feature.invoices.R.string.provider).performScrollTo().assertTextContains("Comercio", substring = true)
        }
    }
    @Test fun payrollTypeOpensItsFieldsAndInvalidGrossIsReportedLocally() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            manual(true)
            shot("income-new")
            field(com.gastos.feature.incomes.R.string.amount).performTextInput("1500,50")
            field(com.gastos.feature.incomes.R.string.concept).performTextInput("Nómina de pruebas sin guardar")
            compose.onAllNodesWithText(text(com.gastos.feature.incomes.R.string.document_payroll)).onFirst().performScrollTo().performClick()
            field(com.gastos.feature.incomes.R.string.gross_amount).performScrollTo().assertTextContains("")
            field(com.gastos.feature.incomes.R.string.gross_amount).performTextInput("no válido")
            compose.onNodeWithText(text(com.gastos.common.R.string.manual_save_income)).assertIsDisplayed().performClick()
            field(com.gastos.feature.incomes.R.string.gross_amount).assertIsFocused()
            compose.onNodeWithText(text(com.gastos.feature.incomes.R.string.validation_gross_net_positive)).assertIsDisplayed()
            shot("payroll-error-keyboard")
            scenario.recreate()
            field(com.gastos.feature.incomes.R.string.gross_amount).performScrollTo().assertTextContains("no válido")
            assertEquals("",field(com.gastos.feature.incomes.R.string.net_amount).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
            shot("payroll-recovered")
        }
    }
    @Test fun englishDarkSmallViewportAndLargeTextKeepTheSaveActionVisible() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val config = Configuration(activity.resources.configuration).apply { setLocale(Locale.ENGLISH); fontScale=1.5f }
                val localized = ContextThemeWrapper(activity,android.R.style.Theme_Material_Light_NoActionBar).apply { applyOverrideConfiguration(config) }
                val model = androidx.lifecycle.ViewModelProvider(activity)[com.gastos.feature.invoices.EditInvoiceViewModel::class.java]
                model.prepareDefaults()
                activity.setContent {
                    CompositionLocalProvider(LocalContext provides localized,LocalConfiguration provides config,
                        LocalProvidableLocaleList provides androidx.compose.ui.text.intl.LocaleList("en-US"),
                        LocalDensity provides Density(activity.resources.displayMetrics.widthPixels/320f,1.5f)) {
                        GastosEIngresosTheme("dark") { com.gastos.feature.invoices.EditInvoiceScreen(0L,{},model) }
                    }
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Save expense").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Save expense").assertIsDisplayed()
            compose.onNode(hasText("Total") and hasSetTextAction()).performTextInput("12.50")
            compose.onNodeWithText("Save expense").assertIsDisplayed()
            shot("expense-en-dark-320-large-keyboard")
        }
    }
}
