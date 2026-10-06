package com.gastos.di

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.common.R
import com.gastos.common.design.MovementListHeader
import com.gastos.common.design.PeriodSelector
import com.gastos.domain.model.ConversionSummary
import com.gastos.domain.model.MoneyRecord
import com.gastos.ui.theme.GastosEIngresosTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/** UI-only fixtures: no financial records, credentials or remote operations. */
@RunWith(AndroidJUnit4::class)
class EssentialPolishUiTest {
    @get:Rule val compose = createComposeRule()
    private fun text(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun searchSurvivesRestorationAndExplicitCloseClearsTheQuery() {
        val restoration: StateRestorationTester = StateRestorationTester(compose)
        restoration.setContent {
            var query: String by rememberSaveable { mutableStateOf("") }
            GastosEIngresosTheme {
                Column { MovementListHeader("Total", 100.0, "EUR", null, {}, query, { query = it }) }
            }
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithContentDescription(text(R.string.movements_search)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Mercado")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assertTextContains("Mercado")
        compose.onNodeWithContentDescription(text(R.string.movements_search_close)).performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithContentDescription(text(R.string.movements_search)).performClick()
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun singlePeriodControlRestoresAllPeriodsAndCancelDoesNotApplyChanges() {
        val initialStart: Long = LocalDate.of(2026, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val initialEnd: Long = LocalDate.of(2026, 1, 31).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        var start: Long? = initialStart
        var end: Long? = initialEnd
        var changes: Int = 0
        compose.setContent {
            var selection: Pair<Long?, Long?> by rememberSaveable { mutableStateOf(start to end) }
            GastosEIngresosTheme {
                PeriodSelector(selection.first, selection.second) { first, last ->
                    start = first; end = last; selection = first to last; changes++
                }
            }
        }
        compose.onNode(isSelectable()).performClick()
        compose.onNodeWithText(text(R.string.essential_apply)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(initialStart, start); assertEquals(initialEnd, end); assertEquals(1, changes) }
        compose.onNode(isSelectable()).performClick()
        compose.onNodeWithText(text(R.string.essential_all_periods)).performClick()
        compose.runOnIdle { assertNull(start); assertNull(end); assertEquals(2, changes) }
        compose.onNodeWithText(text(R.string.essential_all_periods)).assertIsSelected().performClick()
        compose.onNodeWithText(text(R.string.essential_cancel)).performClick()
        compose.runOnIdle { assertEquals(2, changes) }
    }

    @Test fun partialTotalKeepsItsExplanationAndRateRefreshAction() {
        var refreshed: Boolean = false
        val summary: ConversionSummary = ConversionSummary(100.0,
            listOf(MoneyRecord("synthetic", "Missing rate", 20.0, "USD")), setOf("USD"), null, 2)
        compose.setContent {
            GastosEIngresosTheme {
                Column { MovementListHeader("Total", summary.amount, "EUR", summary, { refreshed = true }, "", {}) }
            }
        }
        compose.onNodeWithText(text(R.string.essential_partial)).performClick()
        compose.onNodeWithText("Missing rate", substring = true).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.essential_update_rates)).performClick()
        compose.runOnIdle { assertEquals(true, refreshed) }
    }
}
