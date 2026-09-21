@file:Suppress("DEPRECATION")
package com.gastos.di

import android.content.Context
import android.content.ContextWrapper
import android.graphics.*
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.MainActivity
import com.gastos.domain.model.*
import com.gastos.feature.backup.*
import com.gastos.feature.chatbot.DocumentCaptureViewModel
import com.gastos.feature.chatbot.ChatbotViewModel
import com.gastos.feature.settings.SecureStorage
import com.gastos.repository.BackupDataset
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import com.google.api.services.drive.model.File as DriveFile

/** Only run with explicit permission: writes synthetic records and a dedicated Google workbook. */
@RunWith(AndroidJUnit4::class)
class LiveAccountJourneyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val root = File(context.getExternalFilesDir(null), "live-account").apply { mkdirs() }
    private val report = JSONObject().put("syntheticOnly", true)
    private val cases = JSONArray()
    private val runId = "QA-" + System.currentTimeMillis().toString().takeLast(8)
    private lateinit var app: LiveAccountTestEntryPoint
    private lateinit var googleSheets: Sheets
    private lateinit var googleDrive: Drive
    private var book = ""
    private val failures = mutableListOf<String>()
    private val captured = mutableListOf<String>()
    private val fixtures = linkedMapOf<String, File>()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var capture: DocumentCaptureViewModel
    private lateinit var chat: ChatbotViewModel

    @Test fun realAccountJourney(): Unit = runBlocking {
        assumeTrue("Requires explicit -e liveAccount true", InstrumentationRegistry.getArguments().getString("liveAccount") == "true")
        check(context.packageName.endsWith(".dev"))
        app = EntryPointAccessors.fromApplication(context, LiveAccountTestEntryPoint::class.java)
        val key = SecureStorage(context).getString(SecureStorage.KEY_GEMINI_API_KEY)
        check(key.isNotBlank()) { "Gemini is not configured" }
        check(app.sheets().isSignedIn()) { "Google account or permissions missing" }
        app.reader().configureGemini(key, "")
        val account = requireNotNull(app.sheets().getLastSignedInAccount())
        val oldLink = app.sync().getStoredId(account)
        report.put("runId", runId).put("googleConfigured", true).put("geminiConfigured", true)
            .put("premiumInitially", app.premium().isPremium.value)
        app.billing().debugSetPremium(true)
        val credential = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE_FILE)).setSelectedAccount(account.account)
        val initializer = com.google.api.client.http.HttpRequestInitializer { request ->
            credential.initialize(request); request.connectTimeout = 15_000; request.readTimeout = 45_000; request.numberOfRetries = 0
        }
        googleSheets = Sheets.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), initializer).setApplicationName("FinAI QA").build()
        googleDrive = Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), initializer).setApplicationName("FinAI QA").build()
        val baseline = app.snapshots().snapshot()
        report.put("initialExpenses", baseline.invoices.size).put("initialIncomes", baseline.incomes.size)
        val privateRoot = File(context.filesDir, "live_account_recovery").apply { mkdirs() }
        val passwordFile = File(privateRoot, "$runId.password")
        passwordFile.writeText(UUID.randomUUID().toString())
        // Keep the user's recovery key untouched, including after successful restore.
        val testContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("qa_${runId}_$name", mode)
        }
        val archive = BackupArchiveService(context, app.snapshots(), app.settings(), app.images(),
            BackupKeyStore(testContext), BackupRestoreJournal(context), app.outbox(), app.cache())
        archive.configurePassword(passwordFile.readText().toCharArray())
        val baselineFile = File(privateRoot, "$runId-before.finai")
        archive.createArchive(baselineFile, BackupMode.COMPLETE)
        report.put("baselineProtectedOnDevice", true)
        persist()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                capture = ViewModelProvider(activity)[DocumentCaptureViewModel::class.java]
                chat = ViewModelProvider(activity)[ChatbotViewModel::class.java]
            }
            step("google_create_dedicated_workbook") {
                book = withContext(Dispatchers.IO) { googleSheets.spreadsheets().create(Spreadsheet().setProperties(
                    SpreadsheetProperties().setTitle("FinAI - PRUEBAS SINTETICAS - $runId"))).execute().spreadsheetId }
                app.sync().setSpreadsheetId(account, book)
                app.sheets().exportToSheets(account, baseline.invoices, baseline.incomes, baseline.products, book)
                report.put("workbookUrl", "https://docs.google.com/spreadsheets/d/$book/edit")
                val titles = withContext(Dispatchers.IO) { googleSheets.spreadsheets().get(book).execute().sheets.map { it.properties.title } }
                assertTrue(titles.containsAll(listOf("Resumen", "Facturas Recibidas", "Ingresos", "Productos", "Impuestos", "Análisis personal")))
            }
            addFixtures()
            for ((name, file) in fixtures) {
                step("capture_$name") {
                    val start = SystemClock.elapsedRealtime()
                    scenario.onActivity { capture.processImage(Uri.fromFile(file)) }
                    withTimeout(210_000) { while (capture.state.value.busy) delay(100) }
                    val state = capture.state.value
                    val timing = JSONObject().put("fixture", name).put("durationMs", SystemClock.elapsedRealtime() - start)
                        .put("saved", state.saved != null).put("issues", JSONArray(state.issues.map { "${it.field}:${it.reason}" }))
                        .put("message", state.message)
                    report.optJSONArray("captures")?.put(timing) ?: report.put("captures", JSONArray().put(timing))
                    if (name == "incomplete") {
                        assertNull("An unreadable total must not become an invented amount", state.saved)
                        assertNotNull("Original must remain available for retry", state.selected)
                    } else {
                        val identity = requireNotNull(state.saved) { "No automatic save: ${state.message}; ${state.issues}" }
                        captured += identity.uuid
                        val data = app.snapshots().snapshot()
                        val invoice = data.invoices.find { it.documentUuid == identity.uuid }
                        val income = data.incomes.find { it.documentUuid == identity.uuid }
                        val evidence = requireNotNull(invoice?.evidence ?: income?.evidence)
                        File(root, "$name-extraction.json").writeText(DocumentEvidenceCodec.encode(evidence))
                        when (name) {
                            "inconsistent" -> assertEquals(999.0, invoice!!.total, 0.001)
                            "mixed-vat" -> {
                                assertEquals(202.0, invoice!!.total, 0.001); assertNull(invoice.ivaPercent)
                                assertEquals(listOf(4.0, 10.0, 21.0), invoice.taxes.filter { it.effect == TaxEffect.CHARGE }.mapNotNull { it.rate }.sorted())
                                assertEquals(3, data.products.count { it.invoiceId == invoice.id })
                            }
                            "zero-vat" -> {
                                assertEquals(171.0, invoice!!.total, 0.001)
                                assertTrue(data.products.any { it.invoiceId == invoice.id && it.ivaPercent == 0.0 })
                            }
                            "payroll" -> {
                                assertEquals(1670.0, income!!.monto, 0.001)
                                assertEquals(2000.0, income.totalDevengado, 0.001)
                                assertEquals(1670.0, income.totalNeto, 0.001)
                                assertEquals(130.0, evidence.document.socialSecurity!!, 0.001)
                            }
                            "issued-invoice" -> {
                                assertNotNull(income); assertEquals(121.0, income!!.monto, 0.001)
                                assertNull(evidence.document.gross); assertNull(evidence.document.net)
                            }
                        }
                        assertTrue("Chat receipt missing", data.chatMessages.any { it.documentUuid == identity.uuid })
                    }
                }
            }
            step("duplicate_same_photo_fast") {
                val before = app.snapshots().snapshot()
                scenario.onActivity { capture.processImage(Uri.fromFile(fixtures.getValue("mixed-vat"))) }
                withTimeout(20_000) { while(capture.state.value.busy) delay(50) }
                assertTrue(capture.state.value.duplicates.isNotEmpty())
                assertEquals(before.invoices.size, app.snapshots().snapshot().invoices.size)
            }
            step("duplicate_number_different_photo") {
                val before = app.snapshots().snapshot()
                val copy = BitmapFactory.decodeFile(fixtures.getValue("mixed-vat").path).copy(Bitmap.Config.ARGB_8888, true)
                Canvas(copy).drawText("SECOND PHOTO", 50f, copy.height - 30f, Paint().apply { color = Color.GRAY; textSize = 20f })
                val altered = File(root, "same-invoice-second-photo.png")
                altered.outputStream().use { copy.compress(Bitmap.CompressFormat.PNG,100,it) }; copy.recycle()
                scenario.onActivity { capture.processImage(Uri.fromFile(altered)) }
                withTimeout(210_000) { while(capture.state.value.busy) delay(100) }
                assertTrue("Same invoice number not detected", capture.state.value.duplicates.isNotEmpty())
                assertEquals(before.invoices.size, app.snapshots().snapshot().invoices.size)
            }
            step("duplicate_payroll_photo") {
                val before = app.snapshots().snapshot()
                scenario.onActivity { capture.processImage(Uri.fromFile(fixtures.getValue("payroll"))) }
                withTimeout(20_000) { while(capture.state.value.busy) delay(50) }
                assertTrue(capture.state.value.duplicates.isNotEmpty())
                assertEquals(before.incomes.size, app.snapshots().snapshot().incomes.size)
            }
            sendText("text_expense_without_tax", "Registra un gasto de 12,50 EUR en TEST $runId CAFETERIA con fecha 21/09/2026. No dispongo de IVA ni de detalle de productos.") {
                val record = it.invoices.last(); assertEquals(12.50,record.total,0.001); assertNull(record.ivaPercent)
                assertFalse(it.products.any { p -> p.invoiceId == record.id })
            }
            sendText("text_generic_income", "Registra un ingreso de 35,00 EUR por TEST $runId REGALO con fecha 21/09/2026. Es un regalo, no una nomina. No hay datos de impuestos ni bruto ni neto.") {
                val record = it.incomes.last(); assertEquals(35.0,record.monto,0.001)
                assertNull(record.evidence?.document?.gross); assertNull(record.evidence?.document?.net)
                assertEquals(0.0,record.totalDevengado,0.001); assertEquals(0.0,record.totalNeto,0.001)
            }
            step("text_impossible_date_does_not_save") {
                val before = app.snapshots().snapshot()
                send("Registra un gasto TEST $runId INVALIDO de 9 EUR con fecha 31/02/2026.")
                val after = app.snapshots().snapshot()
                assertEquals(before.invoices.size,after.invoices.size); assertEquals(before.incomes.size,after.incomes.size)
            }
            step("drive_automatic_upload_expenses_and_incomes") {
                withTimeout(180_000) {
                    while(true) {
                        val data = app.snapshots().snapshot()
                        val done = data.invoices.filter { it.documentUuid in captured }.all { it.driveFileId != null && !it.driveUploadPending } &&
                            data.incomes.filter { it.documentUuid in captured }.all { it.driveFileId != null && !it.driveUploadPending }
                        if(done && captured.size == 4) break
                        delay(1000)
                    }
                }
                assertTrue(app.outbox().pending().none { it.status == RemoteSyncStatus.FAILED })
            }
            step("sheets_real_rows_taxes_and_summary") {
                app.sync().syncChanges()
                withTimeout(180_000) { while(app.outbox().pending().any { it.target == RemoteSyncTarget.EXPENSE_SHEETS || it.target == RemoteSyncTarget.INCOME_SHEETS }) delay(1000) }
                val data = app.snapshots().snapshot()
                val expenses = values("'Facturas Recibidas'!A1:AZ")
                val incomes = values("'Ingresos'!A1:AZ")
                for (uuid in captured) assertEquals(1, (expenses + incomes).count { row -> row.any { it.toString() == uuid } })
                val summary = values("'Resumen'!A1:B10")
                File(root,"sheets-summary.json").writeText(JSONArray(summary).toString(2))
                assertEquals(data.invoices.filter { it.tipo == InvoiceType.GASTO }.sumOf { it.total }, summary[3][1].toString().toDouble(), 0.01)
                assertEquals(data.incomes.sumOf { it.monto }, summary[4][1].toString().toDouble(), 0.01)
                assertFalse(summary.flatten().any { it.toString().startsWith("#") })
                assertTrue(values("'Impuestos'!A1:M").size > 3)
            }
            step("sheets_personal_columns_and_rebuild") {
                withContext(Dispatchers.IO) {
                    val expenseSheet = googleSheets.spreadsheets().get(book).execute().sheets.first { it.properties.title == "Facturas Recibidas" }
                    googleSheets.spreadsheets().batchUpdate(book, BatchUpdateSpreadsheetRequest().setRequests(listOf(Request().setAppendDimension(
                        AppendDimensionRequest().setSheetId(expenseSheet.properties.sheetId).setDimension("COLUMNS").setLength(2))))).execute()
                    googleSheets.spreadsheets().values().update(book,"'Facturas Recibidas'!AA1:AA2",ValueRange().setValues(listOf(listOf("PERSONAL QA"),listOf("=1+2"))))
                        .setValueInputOption("USER_ENTERED").execute()
                }
                val data = app.snapshots().snapshot()
                app.sheets().exportToSheets(account,data.invoices,data.incomes,data.products,book)
                assertEquals("=1+2", values("'Facturas Recibidas'!AA2", "FORMULA")[0][0])
                assertEquals(3.0, values("'Facturas Recibidas'!AA2")[0][0].toString().toDouble(),0.001)
            }
            step("drive_idempotent_upload_and_download") {
                val data = app.snapshots().snapshot()
                val expense = data.invoices.first { it.documentUuid in captured }
                val income = data.incomes.first { it.documentUuid in captured }
                assertEquals(expense.driveFileId, app.drive().upload(expense).invoice.driveFileId)
                assertEquals(income.driveFileId, app.drive().upload(income).income.driveFileId)
                val expenseImage = app.cache().resolve(null,expense.driveFileId,expense.driveAccountId,expense.driveContentHash)
                val incomeImage = app.cache().resolve(null,income.driveFileId,income.driveAccountId,income.driveContentHash)
                assertNotNull(BitmapFactory.decodeFile(expenseImage.path)); assertNotNull(BitmapFactory.decodeFile(incomeImage.path))
                assertNotEquals(expense.driveFileId,income.driveFileId)
            }
            testBackups(archive, passwordFile)
            step("delete_expense_keeps_drive_photo_and_cleans_sheets") {
                val expense = app.snapshots().snapshot().invoices.first { it.documentUuid in captured }
                app.sync().deleteLocal(expense)
                withTimeout(120_000) { while(values("'Facturas Recibidas'!A1:AZ").any { row -> row.any { it.toString() == expense.documentUuid } }) delay(1000) }
                withContext(Dispatchers.IO) { assertEquals(false, googleDrive.files().get(expense.driveFileId).setFields("trashed").execute().trashed) }
                assertNull(app.invoices().getInvoiceById(expense.id))
            }
        } finally {
            report.put("pending", JSONArray(app.outbox().pending().map { JSONObject().put("target",it.target).put("state",it.status).put("error",it.lastError) }))
            report.put("failures", JSONArray(failures)); persist()
            if(oldLink.isNotBlank()) app.sync().setSpreadsheetId(account,oldLink)
            scenario.close()
        }
        assertTrue("Live failures: ${failures.joinToString()}; inspect live-account/report.json", failures.isEmpty())
    }

    private suspend fun sendText(name: String, text: String, verify: (BackupDataset) -> Unit) = step(name) {
        send(text); verify(app.snapshots().snapshot())
    }
    private suspend fun send(text: String) {
        withTimeout(30_000) { while(chat.uiState.value.isProcessing) delay(100) }
        scenario.onActivity { chat.sendMessage(text) }
        withTimeout(120_000) { while(chat.uiState.value.isProcessing) delay(100) }
        report.put("lastTextResult", chat.uiState.value.messages.takeLast(2).toString()); persist()
    }
    private suspend fun values(range: String, render: String = "UNFORMATTED_VALUE"): List<List<Any>> = withContext(Dispatchers.IO) {
        googleSheets.spreadsheets().values().get(book,range).setValueRenderOption(render).execute().getValues().orEmpty()
    }
    private suspend fun step(name: String, block: suspend () -> Unit) {
        val started = SystemClock.elapsedRealtime()
        val item = JSONObject().put("name",name)
        try { block(); item.put("passed",true) }
        catch(error: Throwable) {
            failures += name
            item.put("passed",false).put("errorClass",error.javaClass.simpleName)
                .put("error",error.message.orEmpty().replace(Regex("AIza[0-9A-Za-z_-]+"),"[REDACTED]").take(600))
        }
        item.put("durationMs",SystemClock.elapsedRealtime()-started); cases.put(item); persist()
        println("FINAI_QA $name passed=${item.optBoolean("passed")} ms=${item.optLong("durationMs")}")
    }
    private fun persist() { report.put("cases",cases); File(root,"report.json").writeText(report.toString(2)) }

    private suspend fun testBackups(archive: BackupArchiveService, password: File) {
        val dataOnly = File(root,"$runId-data.finai")
        val complete = File(root,"$runId-complete.finai")
        step("encrypted_data_and_complete_backups") {
            val small = archive.createArchive(dataOnly,BackupMode.DATA_ONLY)
            val full = archive.createArchive(complete,BackupMode.COMPLETE)
            assertEquals(0,small.imageCount); assertTrue(full.imageCount >= 4)
            assertTrue(complete.length() > dataOnly.length())
            report.put("dataBackupBytes",dataOnly.length()).put("completeBackupBytes",complete.length())
        }
        val expected = app.snapshots().snapshot()
        step("wrong_password_no_mutation") {
            val result = runCatching { dataOnly.inputStream().use { archive.restore(it,"incorrect-test-password".toCharArray()) } }
            assertTrue(result.isFailure); assertEquals(expected, app.snapshots().snapshot())
        }
        step("truncated_backup_no_mutation") {
            val bytes = dataOnly.readBytes().dropLast(30).toByteArray()
            assertTrue(runCatching { archive.restore(bytes.inputStream(),password.readText().toCharArray()) }.isFailure)
            assertEquals(expected,app.snapshots().snapshot())
        }
        step("data_restore_recovers_edit_and_drive_links") {
            val expense = expected.invoices.first { it.documentUuid in captured }
            app.invoices().updateInvoice(expense.copy(notas="QA TEMPORARY EDIT"))
            dataOnly.inputStream().use { archive.restore(it,password.readText().toCharArray()) }
            val after = app.snapshots().snapshot()
            assertEquals(expected.invoices.map { it.documentUuid }.sorted(),after.invoices.map { it.documentUuid }.sorted())
            for (old in expected.invoices) {
                val record = after.invoices.single { it.documentUuid == old.documentUuid }
                assertEquals(old.total,record.total,0.001); assertEquals(old.taxes,record.taxes)
                assertEquals(old.driveFileId,record.driveFileId); assertEquals(old.notas,record.notas)
            }
            assertEquals(expected.incomes.map { it.documentUuid }.sorted(),after.incomes.map { it.documentUuid }.sorted())
            assertEquals(expected.chatMessages,after.chatMessages)
            assertTrue(app.outbox().pending().none { it.action == RemoteSyncAction.DELETE && it.deleteConsent })
        }
        step("complete_restore_preserves_photos_and_taxes") {
            val result = complete.inputStream().use { archive.restore(it,password.readText().toCharArray()) }
            assertTrue(result.restoredImages >= 4)
            val after = app.snapshots().snapshot()
            after.invoices.filter { it.documentUuid in captured }.forEach { assertNotNull(app.images().managedFile(it.imagenUri)) }
            after.incomes.filter { it.documentUuid in captured }.forEach { assertNotNull(app.images().managedFile(it.imagenUri)) }
            assertEquals(expected.invoices.map { it.taxes },after.invoices.map { it.taxes })
        }
        step("drive_backup_create_list_download_restore") {
            val cloud = CloudBackupService(context,archive,app.sheets(),app.premium())
            val previous = cloud.listBackups()
            val backup = cloud.createBackup()
            assertTrue(cloud.listBackups().any { it.fileId == backup.fileId })
            val downloaded = cloud.downloadBackup(backup.fileId)
            assertEquals(0,downloaded.inputStream().use { archive.inspect(it) }.imageCount)
            // Do not attempt to decrypt a pre-existing recent user backup with the isolated QA key.
            check(previous.none { it.fileId == backup.fileId }) { "A recent existing backup was reused; restore needs its original password" }
            downloaded.inputStream().use { archive.restore(it,password.readText().toCharArray()) }
            assertEquals(expected.invoices.size,app.snapshots().snapshot().invoices.size)
            for (record in app.snapshots().snapshot().incomes.filter { it.documentUuid in captured }) {
                withContext(Dispatchers.IO) { assertEquals(false,googleDrive.files().get(record.driveFileId).setFields("trashed").execute().trashed) }
            }
        }
    }

    private fun addFixtures() {
        val common = listOf("DOCUMENTO SINTETICO - SOLO PRUEBAS - SIN VALIDEZ", "Moneda: EUR   Pais: Espana   Fecha: 21/09/2026")
        render("mixed-vat", common + listOf("FACTURA RECIBIDA - COMPRA", "Emisor: TIENDA TEST $runId", "Numero factura: $runId-ES-001",
            "Precios SIN IVA. Cantidad 1 en cada linea.", "Servicio A       1 x 100,00 = 100,00 EUR     IVA 21%",
            "Servicio B       1 x 50,00 = 50,00 EUR       IVA 10%", "Servicio C       1 x 25,00 = 25,00 EUR       IVA 4%",
            "Base imponible total: 175,00 EUR", "IVA 21%: base 100,00 EUR  cuota 21,00 EUR",
            "IVA 10%: base 50,00 EUR  cuota 5,00 EUR", "IVA 4%: base 25,00 EUR  cuota 1,00 EUR",
            "TOTAL IVA: 27,00 EUR", "TOTAL A PAGAR: 202,00 EUR", "Sin descuento ni retenciones."))
        render("zero-vat",common + listOf("FACTURA RECIBIDA - COMPRA", "Emisor: TIENDA TEST $runId", "Numero factura: $runId-ES-002",
            "Precios SIN IVA. Cantidad 1 en cada linea.","Servicio A       1 x 100,00 = 100,00 EUR     IVA 21%",
            "Servicio exento  1 x 50,00 = 50,00 EUR       IVA 0%", "Base imponible total: 150,00 EUR",
            "IVA 21%: base 100,00 EUR cuota 21,00 EUR", "IVA 0%: base 50,00 EUR cuota 0,00 EUR",
            "TOTAL IVA: 21,00 EUR", "TOTAL A PAGAR: 171,00 EUR", "Sin descuento ni retenciones."))
        render("payroll",common + listOf("NOMINA - RECIBO DE SALARIOS", "Empresa: EMPRESA FICTICIA TEST $runId", "Trabajador: PERSONA SINTETICA TEST",
            "Identificador trabajador: QA-WORKER-001", "Referencia nomina: $runId-NOM-001", "Periodo: 01/09/2026 - 30/09/2026",
            "Pago ordinario mensual", "Salario base: 1800,00 EUR", "Complemento salarial: 200,00 EUR", "TOTAL DEVENGADO BRUTO: 2000,00 EUR",
            "Base de cotizacion: 2000,00 EUR", "Retencion IRPF 10%: 200,00 EUR", "Seguridad Social trabajador: 130,00 EUR",
            "TOTAL DEDUCCIONES: 330,00 EUR", "LIQUIDO NETO A PERCIBIR: 1670,00 EUR", "No hay IVA."))
        render("issued-invoice",common + listOf("FACTURA EMITIDA POR EL USUARIO - INGRESO", "Emisor: PROFESIONAL TEST $runId", "Cliente: CLIENTE FICTICIO QA",
            "Numero factura: $runId-EMI-001", "Servicio de consultoria     1 x 100,00 EUR sin IVA", "Base imponible: 100,00 EUR",
            "IVA 21%: 21,00 EUR", "TOTAL A COBRAR: 121,00 EUR", "Sin retencion. No es una nomina."))
        render("incomplete",listOf("DOCUMENTO SINTETICO INCOMPLETO - SOLO PRUEBAS", "FACTURA RECIBIDA", "Emisor: TEST $runId INCOMPLETO",
            "Numero: $runId-INC", "Fecha: [ILEGIBLE]", "Total: [ILEGIBLE]", "Moneda: EUR", "No se pueden leer otros datos."))
        render("inconsistent",common + listOf("FACTURA RECIBIDA - DATOS INCOHERENTES DE PRUEBA", "Emisor: TEST $runId INCOHERENTE", "Numero: $runId-BAD",
            "Servicio 1 x 100,00 EUR sin IVA", "Base: 100,00 EUR", "IVA 21%: 21,00 EUR", "TOTAL A PAGAR: 999,00 EUR"))
    }
    private fun render(name: String, lines: List<String>) {
        val bitmap = Bitmap.createBitmap(1600,1800,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 32f }
        lines.forEachIndexed { index,line -> canvas.drawText(line,50f,75f+index*76f,paint) }
        val file = File(root,"$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle(); fixtures[name] = file
    }
}
