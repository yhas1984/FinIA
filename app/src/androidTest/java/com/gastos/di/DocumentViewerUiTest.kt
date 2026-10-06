package com.gastos.di

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.feature.backup.DocumentImageButton
import com.gastos.feature.backup.DocumentImageViewModel
import com.gastos.feature.backup.R
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Synthetic local PDF only: does not call Google or write financial records. */
@RunWith(AndroidJUnit4::class)
class DocumentViewerUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fullScreenPdfSupportsAccessibleZoomAndRestoresTheSelectedPage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val services = EntryPointAccessors.fromApplication(context, LiveAccountTestEntryPoint::class.java)
        val file = File(context.cacheDir, "report_exports/viewer-${UUID.randomUUID()}.pdf").apply { parentFile!!.mkdirs() }
        var local: String? = null
        try {
            val document = PdfDocument()
            try {
                repeat(2) { index ->
                    val page = document.startPage(PdfDocument.PageInfo.Builder(600, 900, index + 1).create())
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawText("SYNTHETIC DOCUMENT ${index + 1}", 35f, 70f, Paint().apply { color = Color.BLACK; textSize = 20f })
                    page.canvas.drawText("SYNTHETIC DOCUMENT", 300f, 430f, Paint().apply {
                        color = Color.BLACK; textSize = 30f; textAlign = Paint.Align.CENTER
                    })
                    page.canvas.drawText("PAGE ${index + 1}", 300f, 500f, Paint().apply {
                        color = Color.BLACK; textSize = 52f; textAlign = Paint.Align.CENTER
                    })
                    document.finishPage(page)
                }
                file.outputStream().use(document::writeTo)
            } finally { document.close() }
            val source = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            local = runBlocking { services.images().persist(source).toString() }
            val model = DocumentImageViewModel(services.cache(), context)
            val folder = File(context.filesDir, "consolidation-viewer").apply { mkdirs() }
            File(folder, "viewer-latency.json").delete()
            fun capture(stage: String): String {
                val tree = runCatching {
                    val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
                    roots.fetchSemanticsNodes().indices.joinToString("\n\n") { index -> roots[index].printToString() }
                }.getOrElse { "Unable to read semantics: $it" }
                File(folder, "$stage-semantics.txt").writeText(tree)
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                    try {
                        File(folder, "$stage.png").outputStream().use {
                            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                        }
                    } finally { bitmap.recycle() }
                }
                return tree
            }
            fun waitForPage(expected: Int, stage: String) {
                try {
                    compose.waitUntil(10_000) {
                        compose.onAllNodesWithContentDescription(context.getString(R.string.document_page, expected)).fetchSemanticsNodes().isNotEmpty()
                    }
                } catch (failure: Throwable) {
                    val diagnostic = runCatching { capture(stage) }.getOrElse { "Unable to capture viewer: $it" }
                    Log.e("DocumentViewerQA", "stage=$stage expectedPage=$expected\n$diagnostic", failure)
                    throw AssertionError("Document viewer failed at $stage. Evidence: ${folder.path}", failure)
                }
            }
            fun assertZoom(percent: Int, stage: String) {
                try { compose.onNodeWithText(context.getString(R.string.document_zoom_reset, percent)).assertIsDisplayed() }
                catch (failure: Throwable) {
                    val diagnostic = runCatching { capture(stage) }.getOrElse { "Unable to capture viewer: $it" }
                    Log.e("DocumentViewerQA", "stage=$stage expectedZoom=$percent\n$diagnostic", failure)
                    throw failure
                }
            }
            val restoration = StateRestorationTester(compose)
            restoration.setContent { MaterialTheme { DocumentImageButton(local, null, null, null, model) } }
            compose.onNodeWithText(context.getString(R.string.view_document_source)).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(context.getString(R.string.document_next_page)).fetchSemanticsNodes().isNotEmpty() }
            val nextStarted = android.os.SystemClock.elapsedRealtime()
            compose.onNodeWithContentDescription(context.getString(R.string.document_next_page)).performClick()
            waitForPage(2, "after-next")
            val nextMillis = android.os.SystemClock.elapsedRealtime() - nextStarted
            compose.onNodeWithContentDescription(context.getString(R.string.document_zoom_in)).performClick()
            assertZoom(150, "after-next-zoom-failure")
            capture("page-2-zoom-150")
            val restoreStarted = android.os.SystemClock.elapsedRealtime()
            restoration.emulateSavedInstanceStateRestore()
            waitForPage(2, "after-restore")
            val restoreMillis = android.os.SystemClock.elapsedRealtime() - restoreStarted
            compose.onNodeWithText(context.getString(R.string.document_page_count, 2, 2)).assertIsDisplayed()
            assertZoom(150, "after-restore-zoom-failure")
            compose.onNodeWithContentDescription(context.getString(R.string.document_previous_page)).assertIsEnabled()
            compose.onNodeWithContentDescription(context.getString(R.string.document_next_page)).assertIsNotEnabled()
            capture("restored-page-2-zoom-150")
            compose.onNodeWithContentDescription(context.getString(R.string.document_previous_page)).performClick()
            waitForPage(1, "return-to-page-1")
            assertZoom(100, "page-1-independent-zoom-failure")
            compose.onNodeWithContentDescription(context.getString(R.string.document_next_page)).performClick()
            waitForPage(2, "return-to-page-2")
            assertZoom(150, "page-2-retained-zoom-failure")
            compose.onNodeWithContentDescription(context.getString(R.string.image_close)).performClick()
            compose.onAllNodes(isDialog()).assertCountEquals(0)
            File(folder, "viewer-latency.json").writeText(org.json.JSONObject()
                .put("result", "PASS")
                .put("recordedAtEpochMs", System.currentTimeMillis())
                .put("fixture", "synthetic-two-page-pdf")
                .put("nextPageReadyMillis", nextMillis)
                .put("restoredPageReadyMillis", restoreMillis)
                .put("acceptanceTimeoutMillis", 10_000)
                .put("page", 2).put("zoomPercent", 150)
                .put("immediateNext", true).put("independentPageZoom", true)
                .toString(2))
            Log.i("DocumentViewerQA", "nextPageReadyMillis=$nextMillis restoredPageReadyMillis=$restoreMillis")
        } finally { services.images().delete(local); file.delete() }
    }
}
