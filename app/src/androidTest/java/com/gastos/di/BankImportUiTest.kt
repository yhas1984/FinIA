package com.gastos.di

import android.content.ContextWrapper
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.MainActivity
import com.gastos.R
import com.gastos.bank.*
import com.gastos.domain.model.*
import com.gastos.local.database.AppDatabase
import com.gastos.repository.CurrencyPreference
import com.gastos.storage.BankImportStore
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** CSV preparation only, in an isolated DB. No ledger or cloud writes. */
@RunWith(AndroidJUnit4::class)
class BankImportUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun preamblePreviewExplicitExclusionAndMappingCorrectionStayRecoverable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("qa-bank-ui-$name", mode)
        }
        val store = BankImportStore(isolated, db)
        val account = runBlocking { store.account("Synthetic UI", "EUR") }
        val saved = SavedStateHandle(mapOf("bank_account" to account.id))
        val file = File(context.cacheDir, "camera/qa-bank-ui.csv").apply {
            parentFile!!.mkdirs()
            writeText("Synthetic account\nFecha;Concepto;Importe\n05/10/2026;Synthetic shop;-20\n05/10/2026;Synthetic salary;1000\n31/02/2026;Invalid;-2\n")
        }
        val sheets = EntryPointAccessors.fromApplication(context, LiveAccountTestEntryPoint::class.java).sync()
        lateinit var model: BankImportViewModel
        fun text(id: Int, vararg args: Any) = context.getString(id, *args)
        fun scrollToText(id: Int, vararg args: Any): SemanticsNodeInteraction {
            // LazyColumn disposes off-screen items after recreation; locate through its scroll action first.
            val label = text(id, *args)
            compose.waitUntil(10_000) {
                try {
                    compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label))
                    compose.onAllNodesWithText(label).fetchSemanticsNodes().size == 1
                } catch (_: AssertionError) { false }
            }
            return compose.onNodeWithText(label)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun content() = scenario.onActivity { activity ->
                    val factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(clazz: Class<T>): T = BankImportViewModel(isolated, store, db, saved,
                            object : CurrencyPreference { override val defaultCurrency = MutableStateFlow("EUR") }, sheets) as T
                    }
                    model = ViewModelProvider(activity, factory)[BankImportViewModel::class.java]
                    activity.setContent { MaterialTheme { BankImportScreen({}, { _, _ -> }, model = model) } }
                }
                content()
                scenario.onActivity { model.read(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)) }
                compose.waitUntil(20_000) { !model.busy.value && model.table.value != null }
                assertEquals(1, model.headerLine.value)
                scrollToText(R.string.bank_preview).performClick()
                compose.waitUntil(10_000) { model.preview.value.size == 3 && !model.busy.value }
                scrollToText(R.string.bank_preview_count, 2, 1).assertIsDisplayed()
                scrollToText(R.string.bank_exclude_invalid).assertIsOff()
                scrollToText(R.string.bank_stage).assertIsNotEnabled()
                scrollToText(R.string.bank_exclude_invalid).performClick().assertIsOn()
                val modelBeforeRecreation = model
                val tableBeforeRecreation = model.table.value
                val previewBeforeRecreation = model.preview.value
                val mappingBeforeRecreation = model.mapping.value
                scenario.recreate(); content()
                assertSame("Configuration recreation must retain the import operation", modelBeforeRecreation, model)
                assertEquals("Imported CSV must remain available", tableBeforeRecreation, model.table.value)
                assertEquals("Preview must survive recreation", previewBeforeRecreation, model.preview.value)
                assertEquals("Mapping must survive recreation", mappingBeforeRecreation, model.mapping.value)
                compose.waitUntil(10_000) { compose.onAllNodesWithText(text(R.string.bank_title)).fetchSemanticsNodes().isNotEmpty() }
                scrollToText(R.string.bank_preview_count, 2, 1).assertIsDisplayed()
                scrollToText(R.string.bank_exclude_invalid).assertIsOn()
                scrollToText(R.string.bank_stage).assertIsEnabled().performClick()
                compose.waitUntil(10_000) { !model.busy.value && model.table.value == null }
                assertEquals(2, runBlocking { store.transactions().size })
                assertEquals(0, runBlocking { db.invoiceDao().getInvoiceCount() })
                assertEquals(0, runBlocking { db.incomeDao().getIncomeCount() })
                scrollToText(R.string.bank_edit_configuration).performClick()
                compose.waitUntil(10_000) { !model.busy.value && model.table.value != null }
                scenario.onActivity {
                    val mapping = AutomationCodec.json.decodeFromString<BankMapping>(model.mapping.value)
                    model.changeMapping(mapping.copy(reverseSign = true))
                    model.preview()
                }
                compose.waitUntil(10_000) { !model.busy.value && model.preview.value.size == 3 }
                scrollToText(R.string.bank_preview_count, 2, 1).assertIsDisplayed()
                scrollToText(R.string.bank_exclude_invalid).assertIsOff().performClick().assertIsOn()
                scrollToText(R.string.bank_review_rectification).performClick()
                compose.waitUntil(10_000) { model.rectification.value != null && !model.busy.value }
                compose.onNodeWithText(text(R.string.bank_apply_rectification)).performClick()
                compose.waitUntil(10_000) { !model.busy.value && model.table.value == null }
                val corrected = runBlocking { store.transactions() }
                assertEquals(2, corrected.size)
                assertEquals("20", corrected.single { it.description == "Synthetic shop" }.amount)
                assertEquals("-1000", corrected.single { it.description == "Synthetic salary" }.amount)
                assertEquals(0, runBlocking { db.invoiceDao().getInvoiceCount() })
                val importedBatch = model.batchId.value
                val expectedRows = corrected.associateBy { it.id }
                compose.waitUntil(10_000) {
                    !model.busy.value && model.table.value == null && model.rectification.value == null &&
                        model.batchId.value == importedBatch &&
                        model.data.value.bank.batches.any { it.id == importedBatch && it.mapping.reverseSign } &&
                        model.data.value.bank.transactions.filter { it.batchId == importedBatch }.associateBy { it.id } == expectedRows &&
                        compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()
                }
                compose.waitForIdle()
                // Android's dialog-window removal runs outside Compose's virtual animation clock.
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                automation.waitForIdle(300, 5_000)
                android.os.SystemClock.sleep(300)
                compose.waitForIdle()
                compose.onAllNodes(isDialog()).assertCountEquals(0)
                assertEquals(importedBatch, model.batchId.value)
                assertEquals(expectedRows, model.data.value.bank.transactions.filter { it.batchId == importedBatch }.associateBy { it.id })
                val shot = requireNotNull(automation.takeScreenshot())
                try {
                    File(context.cacheDir, "qa-bank-ui.png").outputStream().use {
                        assertTrue(shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { shot.recycle() }
            }
        } finally { file.delete(); db.close() }
    }
}
