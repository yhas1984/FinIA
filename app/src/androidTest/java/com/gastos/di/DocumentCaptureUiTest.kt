package com.gastos.di

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.MainActivity
import com.gastos.data.local.entity.DocumentDraftEntity
import com.gastos.domain.model.*
import com.gastos.feature.chatbot.ChatbotScreen
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class DocumentCaptureUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun pendingDocumentSpanish() = verifySilentCapture("es")
    @Test fun pendingDocumentEnglish() = verifySilentCapture("en")
    @Test fun failedReadStaysInChatWithoutDialog() = verifySilentCapture("es", failed = true)

    @Suppress("DEPRECATION")
    private fun verifySilentCapture(language: String, failed: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val originalLocale = Locale.getDefault()
        val originalConfiguration = Configuration(context.resources.configuration)
        val uuid = "capture-ui-$language"
        val photograph = File(context.filesDir, "document_drafts/$uuid.png").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(700, 800, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.BLACK; textSize = 34f; isAntiAlias = true }
            drawText("SYNTHETIC DOCUMENT", 30f, 70f, paint)
            drawText("Invoice TEST-001", 30f, 145f, paint)
            drawText("Date: unreadable", 30f, 210f, paint)
            drawText("Total: 121.00 EUR", 30f, 290f, paint)
            drawText("VAT: 21%", 30f, 350f, paint)
        }
        photograph.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photograph).toString()
        val database = AppModule.provideDatabase(context)
        val evidence = DocumentEvidence(ScannedDocument(kind = "factura_recibida", issuer = "SYNTHETIC REVIEW", currency = "EUR",
            country = "ES", date = null, total = 121.0, vatPercent = 21.0, taxBase = 100.0, vatAmount = 21.0, linesComplete = true))
        runBlocking {
            database.documentDraftDao().delete(uuid)
            database.documentDraftDao().insert(DocumentDraftEntity(uuid, uuid, uri,
                if (failed) null else DocumentEvidenceCodec.encode(evidence), if (failed) "ERROR" else "REVIEW",
                error = if (failed) "Los modelos no devolvieron una lectura válida." else null))
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val locale = Locale(language)
                    Locale.setDefault(locale)
                    val configuration = Configuration(activity.resources.configuration).apply { setLocale(locale) }
                    activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
                    activity.setContent { MaterialTheme { ChatbotScreen() } }
                }
                val pending = if (language == "es") "Pendientes (" else "Pending ("
                compose.waitUntil(30_000) { compose.onAllNodes(hasText(pending, substring = true)).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(hasText(pending, substring = true)).performClick()
                compose.onNodeWithText(if (failed) "Documento pendiente de lectura" else "SYNTHETIC REVIEW").performClick()
                val review = if (language == "es") "Revisar documento" else "Review document"
                val save = if (language == "es") "Guardar documento" else "Save document"
                compose.onNodeWithText(if (language == "es") "Documento pendiente" else "Document pending").assertIsDisplayed()
                compose.onNodeWithText(if (language == "es") "Reintentar lectura" else "Retry reading").assertIsEnabled()
                compose.onNodeWithText(review).assertDoesNotExist()
                compose.onNodeWithText(save).assertDoesNotExist()
                compose.onAllNodes(isDialog()).assertCountEquals(0)
                // The chat composer is the only editable input; no document form exists.
                compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
                if (failed) compose.onNodeWithText("Los modelos no devolvieron una lectura válida.").assertIsDisplayed()
                compose.waitForIdle()
                // Wait for the pending-document picker dismissal before taking the screenshot.
                instrumentation.uiAutomation.waitForIdle(500, 5_000)
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                File(context.filesDir, "capture-silent-${if (failed) "failure" else language}.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
                compose.onNodeWithContentDescription(if (language == "es") "Ocultar aviso" else "Dismiss notice").performClick()
                compose.onNodeWithTag("capture_status").assertDoesNotExist()
                compose.onNode(hasText(pending, substring = true)).assertIsDisplayed()
            }
            runBlocking {
                val retained = requireNotNull(database.documentDraftDao().get(uuid))
                check(retained.imageUri == uri)
                check(retained.evidenceJson == if (failed) null else DocumentEvidenceCodec.encode(evidence))
                check(database.invoiceDao().documentRecords().none { it.documentUuid == uuid })
                check(database.incomeDao().documentRecords().none { it.documentUuid == uuid })
            }
        } finally {
            runBlocking { database.documentDraftDao().delete(uuid) }
            database.close()
            photograph.delete()
            Locale.setDefault(originalLocale)
            context.resources.updateConfiguration(originalConfiguration, context.resources.displayMetrics)
        }
    }
}
