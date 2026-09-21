@file:Suppress("DEPRECATION")
package com.gastos.di

import android.os.SystemClock
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.MainActivity
import com.gastos.feature.settings.SecureStorage
import com.gastos.feature.backup.BackupKeyStore
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.*
import org.json.JSONArray
import com.gastos.repository.BackupDataset
import com.gastos.repository.RestorableSettings
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in recording controls. Real UI/network; no fabricated results or exported credentials. */
@RunWith(AndroidJUnit4::class)
class DemoRecordingControllerTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun record(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("recordDemo") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.gastos.ingresos.dev")
        val app = EntryPointAccessors.fromApplication(context, LiveAccountTestEntryPoint::class.java)
        val secure = SecureStorage(context)
        val staging = "demo_recording_temporary_key"
        val folder = File(context.getExternalFilesDir(null), "demo-control").apply { mkdirs() }
        if (InstrumentationRegistry.getArguments().getString("resetDemo") == "true") {
            val key = secure.getString(SecureStorage.KEY_GEMINI_API_KEY)
            check(key.isNotBlank())
            secure.putString(staging, key)
            val empty = BackupDataset(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
            withContext(Dispatchers.IO) { app.database().clearAllTables() }
            app.outbox().reconcile(empty)
            app.settings().restoreSettings(RestorableSettings(darkMode = "light"))
            listOf("invoice_images", "document_drafts", "camera", "live_account_recovery").forEach {
                File(context.filesDir, it).deleteRecursively()
            }
            context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            secure.remove(SecureStorage.KEY_GEMINI_API_KEY)
            app.sheets().signOut()
            delay(1500)
            check(app.snapshots().snapshot().invoices.isEmpty())
            File(folder, "reset.json").writeText("{\"localFinancialDataCleared\":true,\"workbookLinkPreserved\":true}")
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        File(folder, "ready").writeText("ready")
        var last = ""
        try {
            val deadline = SystemClock.elapsedRealtime() + 3 * 60 * 60 * 1000L
            while (SystemClock.elapsedRealtime() < deadline) {
                val commandFile = File(folder, "command.json")
                val raw = commandFile.takeIf { it.exists() }?.readText()
                if (raw.isNullOrBlank() || raw == last) { delay(150); continue }
                last = raw
                val command = JSONObject(raw)
                val result = JSONObject().put("id", command.getString("id"))
                try {
                    when (command.getString("action")) {
                        "click" -> compose.onAllNodesWithText(command.getString("text"), substring = command.optBoolean("partial")).onFirst().performClick()
                        "desc" -> compose.onNodeWithContentDescription(command.getString("text")).performClick()
                        "input" -> compose.onAllNodes(hasSetTextAction()).get(command.optInt("index", 0)).performTextReplacement(command.getString("text"))
                        "key" -> {
                            val key = secure.getString(staging)
                            check(key.isNotBlank())
                            compose.onNodeWithText("Gemini API Key").assertIsDisplayed()
                            compose.onNode(hasSetTextAction() and hasText("API Key")).performTextReplacement(key)
                        }
                        "finishKey" -> {
                            check(secure.getString(SecureStorage.KEY_GEMINI_API_KEY).isNotBlank())
                            secure.remove(staging)
                        }
                        "status" -> {
                            val data = app.snapshots().snapshot()
                            result.put("expenses", data.invoices.size).put("incomes", data.incomes.size)
                                .put("products", data.products.size).put("chatMessages", data.chatMessages.size)
                                .put("googleConnected", app.sheets().isSignedIn())
                                .put("geminiConfigured", secure.getString(SecureStorage.KEY_GEMINI_API_KEY).isNotBlank())
                                .put("pending", app.outbox().pending().size)
                        }
                        "records" -> {
                            val data = app.snapshots().snapshot()
                            result.put("expenses", JSONArray(data.invoices.map { invoice -> JSONObject()
                                .put("total", invoice.total).put("taxRates", JSONArray(invoice.taxes.mapNotNull { it.rate }))
                                .put("remoteImage", invoice.driveFileId != null) }))
                            result.put("incomes", JSONArray(data.incomes.map { income -> JSONObject()
                                .put("amount", income.monto).put("gross", income.totalDevengado).put("net", income.totalNeto)
                                .put("remoteImage", income.driveFileId != null) }))
                            result.put("products", data.products.size)
                        }
                        "resetDemoBackup" -> {
                            // Clear only the Dev backup material using its own encrypted preference editor.
                            // This keeps Android's encryption keysets intact; no key material is read/exported.
                            val store = BackupKeyStore(context)
                            store.javaClass.declaredMethods.single { it.name.substringBefore('$') == "clear" && it.parameterCount == 0 }
                                .invoke(store)
                        }
                        "prepareWorkbook" -> withContext(Dispatchers.IO) {
                            val account = requireNotNull(app.sheets().getLastSignedInAccount())
                            val book = app.sync().getStoredId(account)
                            check(book.isNotBlank())
                            val credentials = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE_FILE)).setSelectedAccount(account.account)
                            val sheets = Sheets.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credentials).setApplicationName("FinAI Demo").build()
                            val title = sheets.spreadsheets().get(book).setFields("properties.title").execute().properties.title
                            check(title.startsWith("FinAI - PRUEBAS SINTETICAS") || title == "FinAI - Finanzas de ejemplo")
                            val data = app.snapshots().snapshot()
                            app.sheets().exportToSheets(account, data.invoices, data.incomes, data.products, book)
                            sheets.spreadsheets().batchUpdate(book, BatchUpdateSpreadsheetRequest().setRequests(listOf(
                                Request().setUpdateSpreadsheetProperties(UpdateSpreadsheetPropertiesRequest()
                                    .setProperties(SpreadsheetProperties().setTitle("FinAI - Finanzas de ejemplo")).setFields("title"))
                            ))).execute()
                            check(app.sync().getStoredId(account) == book)
                            result.put("sameWorkbook", true)
                        }
                        "speechFile" -> {
                            val ready = CountDownLatch(1)
                            lateinit var speech: TextToSpeech
                            instrumentation.runOnMainSync {
                                speech = TextToSpeech(context) { ready.countDown() }
                            }
                            check(ready.await(20, TimeUnit.SECONDS))
                            val finished = CountDownLatch(1)
                            speech.language = Locale.forLanguageTag("es-ES")
                            speech.setSpeechRate(0.92f)
                            speech.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                                override fun onStart(id: String?) {}
                                override fun onDone(id: String?) { finished.countDown() }
                                override fun onError(id: String?) { finished.countDown() }
                            })
                            val voice = File(folder, "voice.wav")
                            check(speech.synthesizeToFile(command.getString("text"), null, voice, "demo-voice") == TextToSpeech.SUCCESS)
                            check(finished.await(40, TimeUnit.SECONDS) && voice.length() > 100)
                            speech.shutdown()
                        }
                        "stop" -> break
                        else -> error("Unknown recording action")
                    }
                    compose.waitForIdle()
                    result.put("ok", true)
                } catch (error: Throwable) {
                    // Compose exceptions can contain field values: never serialize their messages.
                    result.put("ok", false).put("errorType", error.javaClass.simpleName)
                }
                File(folder, "result.json").writeText(result.toString())
            }
        } finally { scenario.close() }
    }
}
