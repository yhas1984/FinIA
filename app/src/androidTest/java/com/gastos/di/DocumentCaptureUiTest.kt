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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.MainActivity
import com.gastos.data.local.entity.DocumentDraftEntity
import com.gastos.data.local.entity.ChatMessageEntity
import com.gastos.domain.model.*
import com.gastos.feature.chatbot.ChatbotScreen
import com.gastos.feature.chatbot.DocumentCaptureViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class DocumentCaptureUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun unreadableAmountSpanishHasRetryWithoutPendingDocument() = verifySilentCapture("es")
    @Test fun unreadableAmountEnglishHasRetryWithoutPendingDocument() = verifySilentCapture("en")
    @Test fun failedReadStaysInChatWithoutDialog() = verifySilentCapture("es", failed = true)
    @Test fun savedReceiptAppearsLiveAndAfterRecreationSpanish() = verifySavedReceipt("es")
    @Test fun savedReceiptAppearsLiveAndAfterRecreationEnglish() = verifySavedReceipt("en")

    @Suppress("DEPRECATION")
    private fun verifySavedReceipt(language: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val database = dagger.hilt.android.EntryPointAccessors.fromApplication(context, ChatHistoryTestEntryPoint::class.java).database()
        val originalLocale = Locale.getDefault()
        val originalConfiguration = Configuration(context.resources.configuration)
        val title = if (language == "es") "Gasto registrado" else "Expense recorded"
        val body = if (language == "es") "Tienda de prueba\n28,79 € · 2026-09-20\nReferencia: CHAT-UI-ES" else
            "Test store\n€28.79 · 2026-09-20\nReference: CHAT-UI-EN"
        var messageId = 0L
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun showChat() {
                    scenario.onActivity { activity ->
                        val locale = Locale(language)
                        Locale.setDefault(locale)
                        val configuration = Configuration(activity.resources.configuration).apply { setLocale(locale) }
                        activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
                        activity.setContent { MaterialTheme { ChatbotScreen() } }
                    }
                    compose.waitUntil(30_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
                }
                showChat()
                // No synthetic financial record enters the live database or remote synchronization.
                messageId = runBlocking { database.chatMessageDao().insert(ChatMessageEntity(role = "document",
                    visibleText = "$title\n$body", includeInContext = false)) }
                compose.waitUntil(30_000) { compose.onAllNodes(hasText(body)).fetchSemanticsNodes().isNotEmpty() }
                compose.onAllNodes(hasText(body)).assertCountEquals(1)
                compose.onNodeWithText(body).assertIsDisplayed()
                compose.onAllNodes(isDialog()).assertCountEquals(0)
                scenario.recreate()
                showChat()
                compose.waitUntil(30_000) { compose.onAllNodes(hasText(body)).fetchSemanticsNodes().isNotEmpty() }
                compose.onAllNodes(hasText(body)).assertCountEquals(1)
                compose.onNodeWithText(body).assertIsDisplayed()
                val receipt = compose.onAllNodesWithTag("chat_document_receipt").onLast().captureToImage().asAndroidBitmap()
                File(context.filesDir, "chat-receipt-$language.png").outputStream().use { receipt.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } finally {
            runBlocking { database.openHelper.writableDatabase.execSQL("DELETE FROM chat_messages WHERE id = ?", arrayOf(messageId)) }
            Locale.setDefault(originalLocale)
            context.resources.updateConfiguration(originalConfiguration, context.resources.displayMetrics)
        }
    }

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
            drawText("Total: unreadable", 30f, 290f, paint)
            drawText("VAT: 21%", 30f, 350f, paint)
        }
        photograph.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photograph).toString()
        val database = AppModule.provideDatabase(context)
        val evidence = DocumentEvidence(ScannedDocument(kind = "factura_recibida", issuer = "SYNTHETIC REVIEW", currency = "EUR",
            country = "ES", date = null, total = null, vatPercent = 21.0, taxBase = 100.0, vatAmount = 21.0, linesComplete = true))
        runBlocking {
            database.documentDraftDao().delete(uuid)
            database.documentDraftDao().insert(DocumentDraftEntity(uuid, uuid, uri,
                if (failed) null else DocumentEvidenceCodec.encode(evidence), if (failed) "ERROR" else "REVIEW",
                error = if (failed) "Los modelos no devolvieron una lectura válida." else null))
        }
        try {
            val draft = runBlocking { requireNotNull(database.documentDraftDao().get(uuid)) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var capture: DocumentCaptureViewModel
                scenario.onActivity { activity ->
                    val locale = Locale(language)
                    Locale.setDefault(locale)
                    val configuration = Configuration(activity.resources.configuration).apply { setLocale(locale) }
                    activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
                    capture = ViewModelProvider(activity)[DocumentCaptureViewModel::class.java]
                    activity.setContent { MaterialTheme { ChatbotScreen(captureModel = capture) } }
                }
                val pending = if (language == "es") "Pendientes (" else "Pending ("
                compose.waitUntil(30_000) { capture.state.value.drafts.any { it.uuid == uuid } }
                compose.onNode(hasText(pending, substring = true)).assertDoesNotExist()
                compose.onNodeWithText(if (language == "es") "Comparar velocidad de lectura" else "Compare reading speed").assertDoesNotExist()
                compose.onNodeWithTag("capture_pending_inline").assertDoesNotExist()
                // Exercise the active-document notice directly; historical drafts do not add chat controls.
                scenario.onActivity { capture.open(draft) }
                compose.waitUntil(30_000) { compose.onAllNodesWithTag("capture_status").fetchSemanticsNodes().isNotEmpty() }
                val review = if (language == "es") "Revisar documento" else "Review document"
                val save = if (language == "es") "Guardar documento" else "Save document"
                compose.onNodeWithText(if (language == "es") "Documento pendiente" else "Document pending").assertDoesNotExist()
                compose.onNodeWithText(if (language == "es") "No se pudo leer el documento" else "Could not read the document").assertIsDisplayed()
                compose.onNodeWithText(if (language == "es") "Reintentar lectura" else "Retry reading").assertIsEnabled()
                compose.onNodeWithText(review).assertDoesNotExist()
                compose.onNodeWithText(save).assertDoesNotExist()
                compose.onAllNodes(isDialog()).assertCountEquals(0)
                compose.onNodeWithTag("capture_status")
                    .assert(hasAnyAncestor(hasTestTag("chat_messages")))
                    .assert(!hasAnyAncestor(hasTestTag("chat_composer")))
                // The chat composer is the only editable input; no document form exists.
                compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
                if (failed) compose.onNodeWithText("Los modelos no devolvieron una lectura válida.").assertIsDisplayed()
                compose.waitForIdle()
                // Wait for platform window animations before taking the screenshot.
                instrumentation.uiAutomation.waitForIdle(500, 5_000)
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                File(context.filesDir, "capture-silent-${if (failed) "failure" else language}.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
                compose.onNodeWithContentDescription(if (language == "es") "Ocultar aviso" else "Dismiss notice").performClick()
                compose.onNodeWithTag("capture_status").assertDoesNotExist()
                compose.onNode(hasText(pending, substring = true)).assertDoesNotExist()
                compose.onNodeWithText(if (language == "es") "Comparar velocidad de lectura" else "Compare reading speed").assertDoesNotExist()
                val cleanChat = instrumentation.uiAutomation.takeScreenshot()
                File(context.filesDir, "chat-clean-$language.png").outputStream().use { cleanChat.compress(Bitmap.CompressFormat.PNG, 100, it) }
                cleanChat.recycle()
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
