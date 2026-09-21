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
import com.gastos.domain.model.DocumentEvidenceCodec
import com.gastos.domain.model.DocumentValidator
import com.gastos.feature.ai.DocumentReadResult
import com.gastos.feature.chatbot.ChatbotScreen
import com.gastos.feature.chatbot.DocumentCaptureViewModel
import com.gastos.feature.settings.SecureStorage
import com.gastos.storage.CaptureStart
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in recovery of one user-selected pending document; never deletes or recreates a saved invoice. */
@RunWith(AndroidJUnit4::class)
class PendingInvoiceRecoveryTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun recoverValidatedDraftAndCompareFreshReading(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uuid = InstrumentationRegistry.getArguments().getString("recoverDraft")
        assumeTrue("Requires an explicitly selected pending draft", !uuid.isNullOrBlank())
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".dev"))
        val app = EntryPointAccessors.fromApplication(context, LiveAccountTestEntryPoint::class.java)
        val draft = requireNotNull(app.captureStore().get(requireNotNull(uuid)))
        val original = requireNotNull(DocumentEvidenceCodec.decode(draft.evidenceJson))
        val expected = DocumentValidator.validate(original)
        assertTrue(expected.issues.toString(), expected.issues.isEmpty())
        val report = JSONObject()
        val key = SecureStorage(context).getString(SecureStorage.KEY_GEMINI_API_KEY)
        check(key.isNotBlank())
        app.reader().configureGemini(key, "")
        val readStarted = SystemClock.elapsedRealtime()
        val fresh = app.reader().readDocument(Uri.parse(draft.imageUri))
        report.put("freshReadMs", SystemClock.elapsedRealtime() - readStarted)
        val readStatus = if (fresh is DocumentReadResult.NeedsReview) fresh.issues.toString() else fresh::class.simpleName
        assertTrue("Expected a coherent fresh reading: $readStatus", fresh is DocumentReadResult.Ready)
        val read = (fresh as DocumentReadResult.Ready).evidence.document
        assertEquals(expected.document.total!!, read.total!!, 0.001)
        assertEquals(expected.document.taxBase!!, read.taxBase!!, 0.001)
        assertEquals(expected.document.vatAmount!!, read.vatAmount!!, 0.001)
        assertEquals(expected.document.discount!!, read.discount!!, 0.001)
        report.put("freshReadMatchesPrintedAmounts", true)
        val before = app.snapshots().snapshot()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var capture: DocumentCaptureViewModel
            scenario.onActivity { activity ->
                capture = ViewModelProvider(activity)[DocumentCaptureViewModel::class.java]
                activity.setContent { MaterialTheme { ChatbotScreen(captureModel = capture) } }
                capture.open(draft)
            }
            withTimeout(20_000) { while (capture.state.value.busy) delay(50) }
            assertEquals(uuid, capture.state.value.saved?.uuid)
            assertNull(capture.state.value.selected)
            compose.waitForIdle()
            val after = app.snapshots().snapshot()
            assertEquals(before.invoices.size + 1, after.invoices.size)
            assertEquals(before.incomes.size, after.incomes.size)
            val saved = after.invoices.single { it.documentUuid == uuid }
            assertEquals(expected.document.total!!, saved.total, 0.0)
            assertEquals(expected.document.discount!!, saved.evidence!!.document.discount!!, 0.0)
            assertEquals(original.originalExtraction, saved.evidence!!.originalExtraction)
            assertEquals(original.document.lines.first().unitPrice!!,
                after.products.single { it.invoiceId == saved.id }.precioUnitario, 0.0)
            assertEquals(1, after.chatMessages.count { it.documentUuid == uuid })
            assertNull(app.captureStore().get(uuid))
            val duplicate = app.captureStore().start(Uri.parse(saved.imagenUri))
            assertTrue(duplicate is CaptureStart.Duplicate)
            assertEquals(uuid, (duplicate as CaptureStart.Duplicate).records.single().uuid)
            report.put("savedExactlyOnce", true).put("historyReceiptCount", 1).put("duplicateBlocked", true)
                .put("total", saved.total).put("taxBase", saved.baseImponible).put("taxAmount", saved.cuotaIva)
                .put("discount", saved.evidence!!.document.discount).put("productCount", after.products.count { it.invoiceId == saved.id })
            instrumentation.uiAutomation.waitForIdle(500, 5_000)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(context.filesDir, "pending-invoice-recovered.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        }
        File(context.filesDir, "pending-invoice-recovery.json").writeText(report.toString(2))
    }
}
