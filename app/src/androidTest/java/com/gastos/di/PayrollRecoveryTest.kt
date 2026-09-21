package com.gastos.di

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.MainActivity
import com.gastos.domain.model.PayrollDateBasis
import com.gastos.feature.ai.DocumentReadResult
import com.gastos.feature.chatbot.ChatbotScreen
import com.gastos.feature.chatbot.DocumentCaptureViewModel
import com.gastos.feature.settings.SecureStorage
import com.gastos.storage.CaptureStart
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in selected-document recovery. Real images/identifiers/extractions never enter test fixtures or reports. */
@RunWith(AndroidJUnit4::class)
class PayrollRecoveryTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun rereadComparePrintedFactsAndSaveExactlyOnce(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val uuid = arguments.getString("recoverPayroll")
        assumeTrue("Requires an explicitly selected payroll draft and expected printed values", !uuid.isNullOrBlank())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".dev"))
        val app = EntryPointAccessors.fromApplication(context, LiveAccountTestEntryPoint::class.java)
        val draft = requireNotNull(app.captureStore().get(requireNotNull(uuid)))
        val key = SecureStorage(context).getString(SecureStorage.KEY_GEMINI_API_KEY)
        check(key.isNotBlank())
        app.reader().configureGemini(key, "")
        val report = JSONObject()
        val reportFile = File(context.filesDir, "payroll-recovery.json")
        try {
            val started = SystemClock.elapsedRealtime()
            val result = app.reader().readDocument(Uri.parse(draft.imageUri))
            report.put("readMs", SystemClock.elapsedRealtime() - started).put("result", result::class.simpleName)
            if (result is DocumentReadResult.NeedsReview) {
                report.put("issues", JSONArray(result.issues.map { "${it.field}:${it.reason}" }))
                app.captureStore().update(uuid, result.evidence.copy(sourceSha256 = draft.sourceSha256))
            }
            if (result is DocumentReadResult.Failure) report.put("error", result.message)
            assertTrue("Expected valid payroll; see private device report", result is DocumentReadResult.Ready)
            val evidence = (result as DocumentReadResult.Ready).evidence.copy(sourceSha256 = draft.sourceSha256)
            val document = evidence.document
            assertEquals("nomina", document.kind)
            fun checkAmount(name: String, actual: Double?) {
                val expected = requireNotNull(arguments.getString(name)).toDouble()
                assertTrue("$name differs from the selected document", actual != null && kotlin.math.abs(expected - actual) < 0.001)
            }
            checkAmount("expectedGross", document.gross)
            checkAmount("expectedNet", document.net)
            checkAmount("expectedSocialSecurity", document.socialSecurity)
            checkAmount("expectedDeductions", document.payroll?.totalDeductions)
            assertEquals(arguments.getString("expectedDate"), document.date)
            assertEquals(PayrollDateBasis.PERIOD_END, document.payroll!!.dateBasis)
            assertNull(document.payroll!!.paymentDate)
            assertNull(document.withholdingPercent)
            assertNull(document.withholdingAmount)
            assertTrue(document.lines.isEmpty())
            report.put("printedValuesPreserved", true).put("accountingDateFromPrintedPeriod", true)
            val before = app.snapshots().snapshot()
            app.captureStore().update(uuid, evidence)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var capture: DocumentCaptureViewModel
                scenario.onActivity { activity ->
                    capture = ViewModelProvider(activity)[DocumentCaptureViewModel::class.java]
                    activity.setContent { MaterialTheme { ChatbotScreen(captureModel = capture) } }
                    capture.open(draft)
                }
                withTimeout(20_000) { while (capture.state.value.busy) delay(50) }
                assertEquals(uuid, capture.state.value.saved?.uuid)
                val after = app.snapshots().snapshot()
                assertEquals(before.incomes.size + 1, after.incomes.size)
                assertEquals(before.invoices.size, after.invoices.size)
                assertEquals(before.products.size, after.products.size)
                val saved = after.incomes.single { it.documentUuid == uuid }
                assertEquals(evidence.originalExtraction, saved.evidence!!.originalExtraction)
                checkAmount("expectedNet", saved.monto)
                assertEquals(1, after.chatMessages.count { it.documentUuid == uuid })
                val receipt = after.chatMessages.single { it.documentUuid == uuid }
                assertTrue(receipt.visibleText.contains(context.getString(com.gastos.data.R.string.document_chat_period_date)))
                assertNull(app.captureStore().get(uuid))
                val duplicate = app.captureStore().start(Uri.parse(saved.imagenUri))
                assertTrue(duplicate is CaptureStart.Duplicate)
                assertEquals(uuid, (duplicate as CaptureStart.Duplicate).records.single().uuid)
                report.put("savedExactlyOnce", true).put("historyReceiptCount", 1).put("duplicateBlocked", true)
                compose.waitForIdle()
                instrumentation.uiAutomation.waitForIdle(500, 5_000)
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                File(context.filesDir, "payroll-recovered.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
            }
        } finally { reportFile.writeText(report.toString(2)) }
    }
}
