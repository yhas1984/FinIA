package com.gastos.di

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.MainActivity
import com.gastos.automation.AutomationScreen
import com.gastos.automation.WalletCaptureControls
import com.gastos.automation.CategoriesSection
import com.gastos.automation.OrganizationEditorSheet
import com.gastos.automation.OrganizationDraft
import com.gastos.automation.OrganizationEditor
import com.gastos.common.SaveState
import com.gastos.domain.model.Category
import com.gastos.domain.model.DocumentKind
import com.gastos.R
import com.gastos.feature.dashboard.R as DashboardR
import com.gastos.feature.settings.R as SettingsR
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** Reads the catalog and exercises drafts without creating records or granting permissions. */
@RunWith(AndroidJUnit4::class)
class AutomationUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun spanishTabsAndWalletConsentAreReadable() = check("es")
    @Test fun englishTabsAndWalletConsentAreReadable() = check("en")
    @Test fun darkThemeAndLargeEnglishTextRemainUsable() = check("en", dark = true, fontScale = 1.5f)

    @Test fun categoryFiltersAreIndependentFromRulesAndSurviveReturningAndRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithContentDescription(context.getString(DashboardR.string.settings)).performClick()
            compose.onNodeWithText(context.getString(SettingsR.string.automation_settings)).performClick()
            compose.onNodeWithTag("section-CATEGORIES").performClick()
            compose.onNodeWithText(context.getString(R.string.income_title)).performClick().assertIsSelected()
            compose.onNodeWithTag("section-RULES").performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.expenses_title)).assertIsSelected()
            compose.onNodeWithTag("section-CATEGORIES").performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.income_title)).assertIsSelected()
            scenario.recreate()
            compose.onNodeWithText(context.getString(R.string.income_title)).assertIsSelected()
        }
    }

    @Test fun onlyOneCategoryExpandsAndChildEditorKeepsItsParentWithLongLabels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val parent = Category("long-parent", DocumentKind.EXPENSE, "Vehículo profesional y desplazamientos del equipo de reparto")
        val second = Category("second-parent", DocumentKind.EXPENSE, "Otro vehículo")
        val child = Category("child", DocumentKind.EXPENSE, "Combustible y mantenimiento en desplazamientos", parent.id)
        val archived = Category("archived", DocumentKind.EXPENSE, "Archivada de prueba", archived = true)
        val rows = listOf(parent, second, child, archived)
        val expanded = mutableStateOf("")
        val showArchived = mutableStateOf(false)
        val draft = mutableStateOf<OrganizationDraft?>(null)
        var restoredId = ""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(activity.resources.displayMetrics.density, 1.5f)) {
                    MaterialTheme { androidx.compose.foundation.layout.Column {
                        CategoriesSection(rows, DocumentKind.EXPENSE, {}, expanded.value, { expanded.value = it },
                            showArchived.value, { showArchived.value = it }, false,
                            onCreate = { draft.value = OrganizationDraft(OrganizationEditor.CATEGORY, parentId = it) },
                            onRename = {}, onArchive = { restoredId = it.id })
                        draft.value?.let { editor -> OrganizationEditorSheet(editor, rows, SaveState.Idle,
                            editor.name.isNotEmpty(), { draft.value = it }, { draft.value = null }, {}) }
                    } }
                }
            } }
            compose.onNodeWithTag("category-${parent.id}").performClick()
            compose.onNodeWithText(child.name).assertIsDisplayed()
            compose.onNodeWithTag("category-${second.id}").performClick()
            compose.onNodeWithText(child.name).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.show_archived)).performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.organization_options, archived.name)).performClick()
            compose.onNodeWithText(context.getString(R.string.unarchive_category)).performClick()
            compose.runOnIdle { org.junit.Assert.assertEquals(archived.id, restoredId) }
            compose.onNodeWithTag("category-${parent.id}").performClick()
            compose.onNodeWithText(context.getString(R.string.add_subcategory)).performClick()
            compose.runOnIdle { org.junit.Assert.assertEquals(parent.id, draft.value?.parentId) }
            compose.onNodeWithText(context.getString(R.string.subcategory_parent, parent.name)).assertIsDisplayed()
            compose.onNodeWithTag("editor-name").performTextInput("Reparaciones y mantenimiento preventivo")
            screenshot("organization-child-editor-large.png")
        }
    }

    @Test fun dirtyEditorKeepsFieldsAfterErrorsDismissalAndActivityRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithContentDescription(context.getString(DashboardR.string.settings)).performClick()
            compose.onNodeWithText(context.getString(SettingsR.string.automation_settings)).performClick()
            compose.onNodeWithTag("section-CATEGORIES").performClick()
            compose.onNodeWithText(context.getString(R.string.new_category)).performClick()
            compose.onNodeWithTag("editor-name").performTextInput(" ")
            compose.onNodeWithTag("editor-save").performScrollTo().performClick()
            compose.onNodeWithTag("editor-error").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("editor-name").performScrollTo().performTextReplacement("Unsaved QA category")
            compose.onNodeWithContentDescription(context.getString(R.string.automation_dismiss)).performClick()
            compose.onNodeWithText(context.getString(R.string.discard_title)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.continue_editing)).performClick()
            scenario.recreate()
            compose.onNodeWithTag("editor-name").assertTextContains("Unsaved QA category")
            compose.onNodeWithContentDescription(context.getString(R.string.automation_dismiss)).performClick()
            compose.onNodeWithText(context.getString(R.string.discard_changes)).performClick()
            compose.onNodeWithTag("organization-editor").assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.new_category)).assertIsDisplayed()
        }
    }

    @Test fun settingsOpensWalletDirectlyAndRetainsItAfterRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithContentDescription(context.getString(DashboardR.string.settings)).performClick()
            compose.onNodeWithText(context.getString(SettingsR.string.wallet_settings)).assertIsDisplayed().performClick()
            compose.onNodeWithText(context.getString(R.string.wallet_enable)).assertIsDisplayed().assertHasClickAction()
            scenario.recreate()
            compose.onNodeWithText(context.getString(R.string.wallet_enable)).assertIsDisplayed()
            val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            File(context.filesDir, "wallet-direct-settings.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun enabledCaptureWithoutAndroidPermissionCanBeSwitchedOff() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val enabled = mutableStateOf(true)
        var accessRequested = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                WalletCaptureControls(enabled.value, false, { enabled.value = it }, { accessRequested = true })
            } } }
            val control = compose.onNodeWithText(context.getString(R.string.wallet_enable))
            control.assertIsOn()
            compose.onNodeWithText(context.getString(R.string.wallet_access_needed)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.wallet_notification_access)).performClick()
            compose.runOnIdle { org.junit.Assert.assertTrue(accessRequested) }
            control.performClick().assertIsOff()
            compose.onNodeWithText(context.getString(R.string.wallet_disabled)).assertIsDisplayed()
            // Android access and stored activation are independent, so the permission action remains available.
            compose.onNodeWithText(context.getString(R.string.wallet_notification_access)).assertIsDisplayed()
        }
    }

    @Test fun androidPermissionAloneDoesNotEnableCapture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                WalletCaptureControls(false, true, {}, {})
            } } }
            compose.onNodeWithText(context.getString(R.string.wallet_enable)).assertIsOff()
            compose.onNodeWithText(context.getString(R.string.wallet_disabled)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.wallet_active)).assertDoesNotExist()
        }
    }

    @Test fun permissionRevocationKeepsActivationVisibleAndAllowsImmediateDisable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val enabled = mutableStateOf(true)
        val access = mutableStateOf(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                WalletCaptureControls(enabled.value, access.value, { enabled.value = it }, {})
            } } }
            compose.onNodeWithText(context.getString(R.string.wallet_active)).assertIsDisplayed()
            compose.runOnIdle { access.value = false }
            compose.onNodeWithText(context.getString(R.string.wallet_enable)).assertIsOn()
            compose.onNodeWithText(context.getString(R.string.wallet_access_needed)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.wallet_enable)).performClick().assertIsOff()
        }
    }

    private fun check(language: String, dark: Boolean = false, fontScale: Float = 1f) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Scope the language to this composition; do not mutate resources
                // shared with later activities or notification services.
                val localized = ContextThemeWrapper(activity, activity.theme).apply {
                    applyOverrideConfiguration(Configuration().apply { setLocale(Locale(language)) })
                }
                activity.setContent {
                    CompositionLocalProvider(
                        LocalContext provides localized,
                        LocalConfiguration provides localized.resources.configuration,
                        LocalDensity provides Density(activity.resources.displayMetrics.density, fontScale)
                    ) { MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) { AutomationScreen(onBack = {}) } }
                }
            }
            val collapsed = if (language == "es") "Plegada" else "Collapsed"
            val expanded = if (language == "es") "Desplegada" else "Expanded"
            listOf("CATEGORIES", "RULES", "LIMITS", "WALLET").forEach { section ->
                compose.onNodeWithTag("section-$section").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, collapsed))
            }
            screenshot("organization-collapsed-$language${if (dark) "-dark-large" else ""}.png")
            compose.onNodeWithTag("section-RULES").performScrollTo().performClick()
            compose.onNodeWithText(if (language == "es") "Nueva regla" else "New rule").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("section-RULES").performScrollTo().performClick()
            compose.onNodeWithText(if (language == "es") "Nueva regla" else "New rule").assertDoesNotExist()
            compose.onNodeWithTag("section-LIMITS").performScrollTo().performClick()
            compose.onNodeWithText(if (language == "es") "Nuevo límite" else "New limit").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("section-WALLET").performScrollTo().performClick()
            compose.onNodeWithTag("section-LIMITS").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, collapsed))
            compose.onNodeWithTag("section-WALLET").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expanded))
            compose.onNodeWithText(if (language == "es") "Capturar pagos de Wallet" else "Capture Wallet payments").performScrollTo().assertIsDisplayed()
            screenshot("organization-wallet-$language${if (dark) "-dark-large" else ""}.png")
            val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            File(context.filesDir, "automation-wallet-$language.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
