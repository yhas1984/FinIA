@file:Suppress("DEPRECATION")
package com.gastos.di

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.MainActivity
import com.gastos.domain.model.*
import com.gastos.feature.backup.BackupScreen
import com.gastos.feature.backup.BackupViewModel
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.Sheets
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in: exercises only the existing linked test workbook. Never creates or deletes a cloud file. */
@RunWith(AndroidJUnit4::class)
class LiveSheetsSingleWorkbookTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun inspectLinkedWorkbook(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("inspectSheetsLink") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".dev"))
        val app = EntryPointAccessors.fromApplication(context,LiveAccountTestEntryPoint::class.java)
        val account = requireNotNull(app.sheets().getLastSignedInAccount())
        val credential = GoogleAccountCredential.usingOAuth2(context,listOf(DriveScopes.DRIVE_FILE)).setSelectedAccount(account.account)
        val drive = Drive.Builder(NetHttpTransport(),GsonFactory.getDefaultInstance(),credential).setApplicationName("FinAI QA").build()
        val book = app.sync().getStoredId(account)
        var linked = drive.files().get(book).setFields("name,mimeType,trashed").execute()
        if (InstrumentationRegistry.getArguments().getString("restoreLinkedSheet") == "true") {
            check(linked.name == InstrumentationRegistry.getArguments().getString("expectedWorkbookName"))
            check(linked.mimeType == "application/vnd.google-apps.spreadsheet")
            drive.files().update(book,com.google.api.services.drive.model.File().setTrashed(false)).execute()
            linked = drive.files().get(book).setFields("name,mimeType,trashed").execute()
            assertFalse(linked.trashed)
        }
        File(context.filesDir,"sheets-link-diagnostic.json").writeText(JSONObject().put("name",linked.name)
            .put("mimeType",linked.mimeType).put("trashed",linked.trashed).toString(2))
    }

    @Test fun oneTapUpdatesEverythingWithoutCreatingWorkbooks(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSingleSheets") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".dev"))
        val app = EntryPointAccessors.fromApplication(context, LiveAccountTestEntryPoint::class.java)
        val account = requireNotNull(app.sheets().getLastSignedInAccount())
        val book = app.sync().getStoredId(account)
        check(book.isNotBlank())
        val credential = GoogleAccountCredential.usingOAuth2(context,listOf(DriveScopes.DRIVE_FILE)).setSelectedAccount(account.account)
        val sheets = Sheets.Builder(NetHttpTransport(),GsonFactory.getDefaultInstance(),credential).setApplicationName("FinAI QA").build()
        val drive = Drive.Builder(NetHttpTransport(),GsonFactory.getDefaultInstance(),credential).setApplicationName("FinAI QA").build()
        fun books(): Set<String> {
            var token: String? = null
            val ids = mutableSetOf<String>()
            do {
                val page = drive.files().list().setQ("trashed=false and mimeType='application/vnd.google-apps.spreadsheet'")
                    .setPageToken(token).setFields("nextPageToken,files(id)").execute()
                ids += page.files.orEmpty().map { it.id }; token = page.nextPageToken
            } while(token != null)
            return ids
        }
        fun rows(range: String, formulas: Boolean = false): List<List<Any>> = sheets.spreadsheets().values().get(book,range)
            .setValueRenderOption(if (formulas) "FORMULA" else "UNFORMATTED_VALUE").execute().getValues().orEmpty()
        fun documentRows(title: String): Map<String,List<Any>> {
            val all = rows("'$title'")
            val column = all.first().indexOf("UUID")
            check(column >= 0)
            val records = all.drop(1).filter { !it.getOrNull(column)?.toString().isNullOrBlank() }
            assertTrue("No duplicate document UUIDs", records.map { it[column].toString() }.distinct().size == records.size)
            return records.associateBy { it[column].toString() }
        }
        val beforeBooks = books()
        val report = JSONObject().put("workbooksBefore",beforeBooks.size)
        val reportFile = File(context.filesDir,"sheets-single-workbook.json")
        val timestamp = System.currentTimeMillis()
        val tax = DocumentTax("IVA",21.0,10.0,2.1,TaxTreatment.TAXABLE)
        var expense = Invoice(fecha=timestamp,proveedor="TEST QA SINGLE SHEETS",tipo=InvoiceType.GASTO,total=12.1,
            ivaPercent=null,taxes=listOf(tax),numeroFactura="QA-SHEETS-$timestamp")
        var income = Income(fecha=timestamp,concepto="TEST QA SINGLE SHEETS",monto=3.21,ivaPercent=null)
        var historical = Invoice(fecha=timestamp,proveedor="TEST QA HISTORICAL INCOME",tipo=InvoiceType.INGRESO,total=4.56,ivaPercent=null)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: BackupViewModel
                scenario.onActivity { activity ->
                    model = ViewModelProvider(activity)[BackupViewModel::class.java]
                    activity.setContent { MaterialTheme { BackupScreen(onNavigateBack={},viewModel=model) } }
                }
                withTimeout(20_000) { while(!model.uiState.value.isPremium) delay(100) }
                suspend fun tap(name: String) {
                    val start = SystemClock.elapsedRealtime()
                    compose.onNodeWithText("Sincronizar cambios").performScrollTo().performClick()
                    assertTrue("Button must wait for remote confirmation",model.uiState.value.isExportingSheets)
                    withTimeout(180_000) { while(model.uiState.value.isExportingSheets) delay(100) }
                    val state = model.uiState.value
                    assertNull("Sync failed",state.sheetsError)
                    withTimeout(10_000) { while(model.uiState.value.sheetsPending != 0 || model.uiState.value.sheetsFailed != 0) delay(100) }
                    assertTrue("Google acknowledgement required",model.uiState.value.sheetsSynced)
                    assertNull(model.uiState.value.sheetsSyncError)
                    report.put(name+"Ms",SystemClock.elapsedRealtime()-start)
                    reportFile.writeText(report.toString(2))
                }
                tap("initialSync")
                val baseline = app.snapshots().financialSnapshot()
                val personalBefore = rows("'Análisis personal'",true)
                val expensesBefore = documentRows("Facturas Recibidas")
                val incomesBefore = documentRows("Ingresos")
                assertTrue("Every local expense must exist remotely",expensesBefore.keys.containsAll(baseline.invoices.filter { it.tipo==InvoiceType.GASTO }.map { it.documentUuid }))
                assertTrue("Every local income must exist remotely",incomesBefore.keys.containsAll(mergeIncomes(baseline.invoices,baseline.incomes).map { it.documentUuid }))
                report.put("allExistingDocumentsSynced",true)
                expense = expense.copy(id=app.invoices().insertInvoiceWithProducts(expense,listOf(Product(invoiceId=0,descripcion="QA product",precioUnitario=12.1,subtotal=12.1,taxes=listOf(tax)))))
                income = income.copy(id=app.incomes().insertIncome(income))
                historical = historical.copy(id=app.invoices().insertInvoice(historical))
                tap("insertSync")
                assertTrue(documentRows("Facturas Recibidas").containsKey(expense.documentUuid))
                assertTrue(documentRows("Ingresos").keys.containsAll(listOf(income.documentUuid,historical.documentUuid)))
                assertTrue(rows("'Productos'").any { expense.documentUuid in it })
                assertTrue(rows("'Impuestos'").any { expense.documentUuid in it })
                report.put("expensesIncomesLegacyProductsTaxes",true)
                expense = expense.copy(proveedor="TEST QA SINGLE SHEETS EDITED")
                income = income.copy(monto=5.43)
                app.invoices().updateInvoice(expense)
                app.incomes().updateIncome(income)
                tap("editSync")
                assertTrue(documentRows("Facturas Recibidas").getValue(expense.documentUuid).contains(expense.proveedor))
                assertTrue(documentRows("Ingresos").getValue(income.documentUuid).any { it.toString().toDoubleOrNull()==5.43 })
                tap("repeatSync")
                assertEquals(personalBefore,rows("'Análisis personal'",true))
                report.put("editsAndRetryWithoutDuplicates",true)
                // Advanced reconstruction must preserve the same book and use a private recovery file.
                val data = app.snapshots().financialSnapshot()
                app.sheets().exportToSheets(account,data.invoices,data.incomes,data.products,book)
                assertTrue(File(context.noBackupFilesDir,"sheets-recovery").listFiles().orEmpty().any { it.extension=="gz" })
                assertEquals(personalBefore,rows("'Análisis personal'",true))
                report.put("privateRecoveryAndPersonalSheetPreserved",true)
                app.sync().deleteLocal(expense)
                app.sync().deleteLocal(income)
                app.sync().deleteLocal(historical.asLegacyIncome())
                tap("deleteSync")
                assertFalse(documentRows("Facturas Recibidas").containsKey(expense.documentUuid))
                assertFalse(documentRows("Ingresos").containsKey(income.documentUuid))
                assertFalse(documentRows("Ingresos").containsKey(historical.documentUuid))
                assertFalse(rows("'Productos'").any { expense.documentUuid in it })
                assertFalse(rows("'Impuestos'").any { expense.documentUuid in it })
                assertTrue(app.sync().getStoredId(account)==book)
                assertTrue("No additional workbook may be created",books()==beforeBooks)
                val evaluated = sheets.spreadsheets().get(book).setIncludeGridData(true)
                    .setFields("sheets(data(rowData(values(effectiveValue(errorValue)))))").execute()
                assertTrue("No cell errors may remain, including in personal analysis",evaluated.sheets.orEmpty()
                    .flatMap { it.data.orEmpty() }.flatMap { it.rowData.orEmpty() }.flatMap { it.getValues().orEmpty() }
                    .none { it.effectiveValue?.errorValue != null })
                report.put("deletionsSynced",true).put("sameWorkbook",true).put("noAdditionalWorkbooks",true)
                compose.onNodeWithText("Todos los cambios están sincronizados.").performScrollTo().assertIsDisplayed()
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.filesDir,"sheets-single-workbook.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                }
                report.put("passed",true)
            }
        } finally {
            if (expense.id>0 && app.invoices().getInvoiceById(expense.id)!=null) app.sync().deleteLocal(expense)
            if (income.id>0 && app.incomes().getIncomeById(income.id)!=null) app.sync().deleteLocal(income)
            if (historical.id>0 && app.invoices().getInvoiceById(historical.id)!=null) app.sync().deleteLocal(historical.asLegacyIncome())
            reportFile.writeText(report.toString(2))
        }
    }
}
