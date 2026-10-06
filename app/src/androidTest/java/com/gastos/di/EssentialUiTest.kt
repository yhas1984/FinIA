package com.gastos.di

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalProvidableLocaleList
import androidx.compose.ui.unit.Density
import android.view.ContextThemeWrapper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.FinAIApp
import com.gastos.MainActivity
import com.gastos.ui.theme.GastosEIngresosTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** Exercises real navigation and failed drafts without adding financial or remote records. */
@RunWith(AndroidJUnit4::class)
class EssentialUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)
    private fun shot(name: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(context.filesDir, "essential-ui/$name.png").apply { parentFile!!.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun back() { androidx.test.espresso.Espresso.pressBack(); compose.waitForIdle() }
    private fun settings() = compose.onNodeWithContentDescription(text(com.gastos.feature.dashboard.R.string.settings)).performClick()

    @Test fun dashboardBotOpensChatAndManualFormRetainsInvalidDraft() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription(text(com.gastos.R.string.open_ai_assistant)).fetchSemanticsNodes().isNotEmpty() }
            shot("home")
            compose.onNodeWithContentDescription(text(com.gastos.R.string.new_expense)).assertDoesNotExist()
            compose.onNodeWithContentDescription(text(com.gastos.R.string.new_income)).assertDoesNotExist()
            compose.onNodeWithContentDescription(text(com.gastos.R.string.open_ai_assistant)).performClick()
            compose.onNodeWithTag("chat_composer").assertIsDisplayed()
            compose.onAllNodes(isDialog()).assertCountEquals(0)
            back()
            compose.onNode(hasText(text(com.gastos.R.string.dashboard_title)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertIsSelected()
            compose.onNodeWithContentDescription(text(com.gastos.R.string.open_ai_assistant)).performClick()
            shot("chat")
            compose.onNodeWithText(text(com.gastos.feature.chatbot.R.string.chatbot_scan)).performClick()
            compose.onNodeWithText(text(com.gastos.feature.chatbot.R.string.manual_expense)).performClick()
            compose.onNode(hasText(text(com.gastos.feature.invoices.R.string.provider)) and hasSetTextAction()).performTextInput("UI draft, never saved")
            compose.onNode(hasText(text(com.gastos.feature.invoices.R.string.total), substring = false) and hasSetTextAction()).performTextInput("10")
            compose.onNodeWithText(text(com.gastos.common.R.string.essential_taxes)).performScrollTo().performClick()
            compose.onNode(hasText(text(com.gastos.feature.invoices.R.string.vat_percent)) and hasSetTextAction()).performScrollTo().performTextReplacement("150")
            compose.onNodeWithContentDescription(text(com.gastos.feature.invoices.R.string.save)).performClick()
            compose.onNodeWithText(text(com.gastos.feature.invoices.R.string.validation_total_percentages)).performScrollTo().assertIsDisplayed()
            scenario.recreate()
            compose.onNode(hasText(text(com.gastos.feature.invoices.R.string.provider)) and hasSetTextAction()).performScrollTo().assertTextContains("UI draft, never saved")
            compose.onNode(hasText(text(com.gastos.feature.invoices.R.string.vat_percent)) and hasSetTextAction()).performScrollTo().assertTextContains("150")
            compose.onNodeWithContentDescription(text(com.gastos.common.R.string.essential_back)).performClick()
            compose.onNodeWithText(text(com.gastos.common.R.string.essential_keep_editing)).performClick()
            compose.onNodeWithContentDescription(text(com.gastos.common.R.string.essential_back)).performClick()
            compose.onNodeWithText(text(com.gastos.common.R.string.essential_discard)).performClick()
            compose.onNodeWithTag("chat_composer").assertIsDisplayed()
        }
    }

    @Test fun settingsConnectionsAndWidgetEditorNavigateWithoutDuplicatedActions() {
        ActivityScenario.launch(MainActivity::class.java).use {
            settings()
            shot("settings")
            compose.onNodeWithText(text(com.gastos.common.R.string.essential_gemini)).performClick()
            compose.onNodeWithText(text(com.gastos.feature.settings.R.string.settings_assistant_instructions)).performScrollTo().assertIsDisplayed()
            shot("gemini")
            back()
            compose.onNodeWithText(text(com.gastos.common.R.string.essential_data)).performClick()
            shot("connections")
            for ((section, screenshot) in listOf(com.gastos.common.R.string.essential_sheets to "sheets", com.gastos.common.R.string.essential_drive to "drive", com.gastos.common.R.string.essential_copies to "backups", com.gastos.common.R.string.essential_reports to "reports")) {
                compose.onNodeWithText(text(section)).performScrollTo().performClick()
                compose.onAllNodesWithText(text(section)).onFirst().assertIsDisplayed()
                shot(screenshot)
                back()
            }
            back()
            compose.onNodeWithText(text(com.gastos.common.R.string.essential_personalize)).performScrollTo().performClick()
            compose.onNodeWithText(text(com.gastos.feature.dashboard.R.string.done)).assertIsDisplayed()
            shot("customize")
            back()
            compose.onNodeWithText(text(com.gastos.feature.settings.R.string.wallet_settings)).performClick()
            compose.onNodeWithText(text(com.gastos.R.string.wallet_enable)).performScrollTo().assertIsDisplayed()
            shot("organization-wallet")
        }
    }

    @Test fun listPlusButtonsOpenManualFormsAndBackReturnsToTheSameList() {
        ActivityScenario.launch(MainActivity::class.java).use {
            for ((tab, field, save) in listOf(
                Triple(com.gastos.R.string.expenses_title, com.gastos.feature.invoices.R.string.provider, com.gastos.common.R.string.manual_save_expense),
                Triple(com.gastos.R.string.income_title, com.gastos.feature.incomes.R.string.concept, com.gastos.common.R.string.manual_save_income)
            )) {
                val selectedTab = hasText(text(tab)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
                compose.onNode(selectedTab).performClick()
                compose.onNodeWithContentDescription(text(com.gastos.R.string.open_ai_assistant)).assertDoesNotExist()
                compose.onAllNodes(hasText("Añadir manualmente") or hasText("Add manually")).assertCountEquals(0)
                val addDescription: Int = if (tab == com.gastos.R.string.expenses_title) com.gastos.R.string.new_expense else com.gastos.R.string.new_income
                compose.onNodeWithContentDescription(text(addDescription)).assertIsDisplayed().performClick()
                val input = hasText(text(field)) and hasSetTextAction()
                compose.waitUntil(10_000) { compose.onAllNodes(input).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(input).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
                compose.onNodeWithText(text(save)).assertIsDisplayed().assertIsEnabled()
                compose.onNodeWithTag("chat_composer").assertDoesNotExist()
                compose.onNodeWithContentDescription(text(com.gastos.common.R.string.essential_back)).performClick()
                compose.onAllNodes(isDialog()).assertCountEquals(0)
                compose.onNode(selectedTab).assertIsSelected()
            }
        }
    }

    @Test fun expenseAndIncomeSupportDirectEditingAndKeepReadOnlyDetails() {
        val database = dagger.hilt.android.EntryPointAccessors.fromApplication(context, ChatHistoryTestEntryPoint::class.java).database()
        val expenses = kotlinx.coroutines.runBlocking { database.invoiceDao().documentRecords() }.filter { it.tipo == com.gastos.domain.model.InvoiceType.GASTO }
        val incomes = kotlinx.coroutines.runBlocking { database.incomeDao().documentRecords() }
        ActivityScenario.launch(MainActivity::class.java).use {
            for ((title, concept, screenshot) in listOf(Triple(com.gastos.R.string.expenses_title, expenses.firstOrNull()?.proveedor, "expenses"), Triple(com.gastos.R.string.income_title, incomes.firstOrNull()?.concepto, "income"))) {
                compose.onNode(hasText(text(title)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
                compose.onNodeWithText(text(com.gastos.common.R.string.essential_all_periods)).assertIsSelected()
                shot(screenshot)
                if (concept != null) {
                    compose.onNodeWithContentDescription(context.getString(com.gastos.common.R.string.manual_edit_movement, concept)).performScrollTo().performClick()
                    val field = if (title == com.gastos.R.string.expenses_title) com.gastos.feature.invoices.R.string.provider else com.gastos.feature.incomes.R.string.concept
                    val input = hasText(text(field)) and hasSetTextAction()
                    compose.waitUntil(10_000) { compose.onAllNodes(input).fetchSemanticsNodes().isNotEmpty() }
                    compose.onNode(input).assertTextContains(concept)
                    compose.onNodeWithText(text(com.gastos.common.R.string.essential_details)).assertDoesNotExist()
                    compose.onNodeWithTag("chat_composer").assertDoesNotExist()
                    shot("$screenshot-direct-edit")
                    back()
                    compose.onAllNodes(isDialog()).assertCountEquals(0)
                    compose.onNode(hasText(text(title)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertIsSelected()
                    compose.onNodeWithText(concept).performScrollTo().performClick()
                    compose.onNodeWithText(text(com.gastos.common.R.string.essential_details)).assertIsDisplayed()
                    shot("$screenshot-detail")
                    val edit = if (title == com.gastos.R.string.expenses_title) com.gastos.feature.invoices.R.string.edit else com.gastos.feature.incomes.R.string.edit
                    compose.onNodeWithContentDescription(text(edit)).performScrollTo().performClick()
                    shot("$screenshot-edit")
                    back()
                    compose.onAllNodes(isDialog()).assertCountEquals(0)
                    compose.onNodeWithText(text(com.gastos.common.R.string.essential_details)).assertIsDisplayed()
                    back()
                }
            }
        }
    }

    @Test fun darkEnglishLargeTextUsesTheSameRealNavigation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val config = Configuration(activity.resources.configuration).apply { setLocale(Locale.ENGLISH); fontScale = 1.5f }
                val localized = ContextThemeWrapper(activity, android.R.style.Theme_Material_Light_NoActionBar).apply {
                    applyOverrideConfiguration(config)
                }
                activity.setContent {
                    CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                        LocalProvidableLocaleList provides androidx.compose.ui.text.intl.LocaleList("en-US"),
                        // A 320 dp viewport exercises small-screen layout on the physical phone.
                        LocalDensity provides Density(activity.resources.displayMetrics.widthPixels / 320f, 1.5f)) {
                        GastosEIngresosTheme("dark") { FinAIApp() }
                    }
                }
            }
            compose.onNodeWithContentDescription("Open FinAI assistant").performClick()
            compose.onNodeWithTag("chat_composer").assertIsDisplayed()
            shot("chat-en-dark-large")
            back()
            compose.onNode(hasText("Expenses") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
            compose.onNodeWithText("All periods").assertIsDisplayed()
            shot("expenses-en-dark-large")
            compose.onNodeWithText("Add manually").assertDoesNotExist()
            compose.onNodeWithContentDescription("New expense").assertIsDisplayed().performClick()
            compose.onNodeWithText("Save expense").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            val database = dagger.hilt.android.EntryPointAccessors.fromApplication(context, ChatHistoryTestEntryPoint::class.java).database()
            val concept = kotlinx.coroutines.runBlocking { database.invoiceDao().documentRecords() }
                .firstOrNull { it.tipo == com.gastos.domain.model.InvoiceType.GASTO }?.proveedor
            if (concept != null) {
                compose.onNodeWithContentDescription("Edit $concept").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(concept).performScrollTo().performClick()
                compose.onNodeWithText("Transaction details").assertIsDisplayed()
                compose.onNodeWithContentDescription("Edit").performScrollTo().assertIsDisplayed()
                shot("detail-en-dark-large")
            }
        }
    }
}
