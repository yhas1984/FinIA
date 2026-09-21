@file:Suppress("DEPRECATION")
package com.gastos.di

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.domain.model.*
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
import com.google.api.services.drive.model.File as DriveFile

@RunWith(AndroidJUnit4::class)
class LiveSheetsRecoveryTest {
    @Test fun migrationLocalesPersonalColumnsAndOwnership(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSheets") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".dev"))
        val app = EntryPointAccessors.fromApplication(context,LiveAccountTestEntryPoint::class.java)
        val account = requireNotNull(app.sheets().getLastSignedInAccount())
        val oldLink = app.sync().getStoredId(account)
        val credential = GoogleAccountCredential.usingOAuth2(context,listOf(DriveScopes.DRIVE_FILE)).setSelectedAccount(account.account)
        val sheets = Sheets.Builder(NetHttpTransport(),GsonFactory.getDefaultInstance(),credential).setApplicationName("FinAI QA").build()
        val drive = Drive.Builder(NetHttpTransport(),GsonFactory.getDefaultInstance(),credential).setApplicationName("FinAI QA").build()
        check(drive.files().get(oldLink).setFields("name").execute().name.contains("PRUEBAS SINTETICAS"))
        val book = drive.files().copy(oldLink,DriveFile().setName("FinAI - PRUEBAS SINTETICAS - migracion y regiones")).setFields("id").execute().id
        val root = File(context.getExternalFilesDir(null),"live-account").apply { mkdirs() }
        val cases = JSONArray()
        val errors = mutableListOf<String>()
        fun save() = File(root,"sheets-recovery.json").writeText(JSONObject().put("workbookUrl","https://docs.google.com/spreadsheets/d/$book/edit").put("cases",cases).toString(2))
        suspend fun step(name: String, test: suspend () -> Unit) {
            val started = SystemClock.elapsedRealtime()
            val result = JSONObject().put("name",name)
            try { test(); result.put("passed",true) }
            catch(error: Throwable) { errors += name; result.put("passed",false).put("error",error.message?.take(500)) }
            cases.put(result.put("durationMs",SystemClock.elapsedRealtime()-started)); save()
        }
        fun rows(range: String, render: String = "UNFORMATTED_VALUE"): List<List<Any>> =
            sheets.spreadsheets().values().get(book,range).setValueRenderOption(render).execute().getValues().orEmpty()
        fun put(range: String, values: List<List<Any>>) { sheets.spreadsheets().values().update(book,range,ValueRange().setValues(values)).setValueInputOption("USER_ENTERED").execute() }
        fun props(): Map<String,String> = drive.files().get(book).setFields("appProperties").execute().appProperties.orEmpty()
        fun setProps(values: Map<String,String>) { drive.files().update(book,DriveFile().setAppProperties(values)).execute() }
        suspend fun rebuild() {
            val data = app.snapshots().snapshot()
            app.sheets().exportToSheets(account,data.invoices,data.incomes,data.products,book)
        }
        app.sync().setSpreadsheetId(account,book)
        try {
            rebuild()
            step("legacy_schema8_numeric_ids_migrate_with_verified_copy") {
                val data = app.snapshots().snapshot()
                val expense = data.invoices.first { invoice -> data.products.any { it.invoiceId == invoice.id } }
                val expenseRows = rows("'Facturas Recibidas'!A1:AZ")
                val expenseRow = expenseRows.indexOfFirst { row -> row.any { it.toString() == expense.documentUuid } } + 1
                check(expenseRow > 1)
                put("'Facturas Recibidas'!O1",listOf(listOf("ID")))
                put("'Facturas Recibidas'!O$expenseRow",listOf(listOf(expense.id)))
                val products = data.products.filter { it.invoiceId == expense.id }
                val productRows = rows("'Productos'!A1:AZ")
                put("'Productos'!H1:I1",listOf(listOf("InvoiceID","ProductID")))
                for(product in products) {
                    val row = productRows.indexOfFirst { it.getOrNull(7)?.toString() == expense.documentUuid && it.getOrNull(0)?.toString() == product.descripcion }+1
                    check(row>1)
                    put("'Productos'!H$row:I$row",listOf(listOf(expense.id,product.id)))
                }
                setProps(props()+mapOf("finaiSchemaVersion" to "8"))
                rebuild()
                assertEquals("9",props()["finaiSchemaVersion"])
                assertEquals(expense.documentUuid,rows("'Facturas Recibidas'!O$expenseRow")[0][0])
                assertEquals(products.size,rows("'Productos'!H2:H").count { it.firstOrNull()?.toString() == expense.documentUuid })
                assertTrue(File(context.noBackupFilesDir,"sheets-recovery").listFiles().orEmpty().any { it.extension == "gz" })
            }
            step("inserted_personal_column_and_formula_survive_reconstruction") {
                val sheetId = sheets.spreadsheets().get(book).execute().sheets.first { it.properties.title == "Facturas Recibidas" }.properties.sheetId
                sheets.spreadsheets().batchUpdate(book,BatchUpdateSpreadsheetRequest().setRequests(listOf(Request().setInsertDimension(
                    InsertDimensionRequest().setRange(DimensionRange().setSheetId(sheetId).setDimension("COLUMNS").setStartIndex(1).setEndIndex(2)))))).execute()
                put("'Facturas Recibidas'!B1:B2",listOf(listOf("MI COLUMNA QA"),listOf("=6*7")))
                rebuild()
                assertEquals("=6*7",rows("'Facturas Recibidas'!B2","FORMULA")[0][0])
                assertEquals(42.0,rows("'Facturas Recibidas'!B2")[0][0].toString().toDouble(),0.0)
            }
            for(locale in listOf("es_ES","en_US","fr_FR")) step("summary_formulas_in_$locale") {
                sheets.spreadsheets().batchUpdate(book,BatchUpdateSpreadsheetRequest().setRequests(listOf(Request().setUpdateSpreadsheetProperties(
                    UpdateSpreadsheetPropertiesRequest().setProperties(SpreadsheetProperties().setLocale(locale)).setFields("locale"))))).execute()
                rebuild()
                val data = app.snapshots().snapshot()
                val summary = rows("'Resumen'!B4:B7")
                assertEquals(data.invoices.filter { it.tipo==InvoiceType.GASTO }.sumOf { it.total },summary[0][0].toString().toDouble(),0.01)
                assertEquals(mergeIncomes(data.invoices,data.incomes).sumOf { it.monto },summary[1][0].toString().toDouble(),0.01)
            }
            step("month_filter_is_preserved_and_changes_totals") {
                put("'Resumen'!B9:B10",listOf(listOf(2026),listOf(8)))
                rebuild()
                assertEquals(0.0,rows("'Resumen'!B4")[0][0].toString().toDouble(),0.0)
                assertEquals(8.0,rows("'Resumen'!B10")[0][0].toString().toDouble(),0.0)
                put("'Resumen'!B9:B10",listOf(listOf(0),listOf(0)))
            }
            step("newer_schema_is_not_overwritten") {
                val original = props()
                try {
                    setProps(original+mapOf("finaiSchemaVersion" to "99"))
                    val before = rows("'Facturas Recibidas'!A1:AZ","FORMULA")
                    assertTrue(runCatching { rebuild() }.exceptionOrNull()?.message.orEmpty().contains("SHEETS_NEWER_SCHEMA"))
                    assertEquals(before,rows("'Facturas Recibidas'!A1:AZ","FORMULA"))
                } finally { setProps(original) }
            }
            step("other_writer_blocks_changes_until_explicit_transfer") {
                val original = props()
                try {
                    setProps(original+mapOf("finaiWriterId" to "qa-other-phone"))
                    assertTrue(runCatching { rebuild() }.exceptionOrNull()?.message.orEmpty().contains("SHEETS_OTHER_DEVICE"))
                    app.sheets().takeOverWriter(account,book)
                    rebuild()
                    assertNotEquals("qa-other-phone",props()["finaiWriterId"])
                } finally { setProps(original) }
            }
            step("local_unsynced_transaction_is_excluded_from_remote_summary") {
                val before = rows("'Resumen'!B4")[0][0].toString().toDouble()
                val synthetic = Invoice(fecha=DocumentValidator.parseDate("2026-09-21")!!,proveedor="QA PENDING REMOTE",tipo=InvoiceType.GASTO,total=7.0,ivaPercent=null)
                val id = app.invoices().insertInvoice(synthetic)
                try {
                    assertEquals(before,rows("'Resumen'!B4")[0][0].toString().toDouble(),0.0)
                    app.sync().upsertExpense(synthetic.copy(id=id))
                    withTimeout(120_000) { while(rows("'Resumen'!B4")[0][0].toString().toDouble()!=before+7) delay(1000) }
                } finally { app.sync().deleteLocal(synthetic.copy(id=id)) }
                withTimeout(120_000) { while(rows("'Resumen'!B4")[0][0].toString().toDouble()!=before) delay(1000) }
            }
        } finally {
            app.sync().setSpreadsheetId(account,oldLink)
            // Refresh the original test book too, so the app opens a corrected summary.
            val data = app.snapshots().snapshot()
            app.sheets().exportToSheets(account,data.invoices,data.incomes,data.products,oldLink)
            save()
        }
        assertTrue(errors.joinToString(),errors.isEmpty())
    }
}
