@file:Suppress("DEPRECATION")
package com.gastos.di

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.Drive
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in real appearance update of the existing Dev test workbook; no creation or data deletion. */
@RunWith(AndroidJUnit4::class)
class LiveSheetsAppearanceTest {
    @Test fun applyAndRepeatPreserveTheSameWorkbookAndEveryExistingValue(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSheetsAppearance") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".dev"))
        val app = EntryPointAccessors.fromApplication(context,LiveAccountTestEntryPoint::class.java)
        val account = requireNotNull(app.sheets().getLastSignedInAccount())
        val id = app.sync().getStoredId(account)
        check(id.isNotBlank())
        val credential = GoogleAccountCredential.usingOAuth2(context,listOf(DriveScopes.DRIVE_FILE)).setSelectedAccount(account.account)
        val sheets = Sheets.Builder(NetHttpTransport(),GsonFactory.getDefaultInstance(),credential).setApplicationName("FinAI QA").build()
        val drive = Drive.Builder(NetHttpTransport(),GsonFactory.getDefaultInstance(),credential).setApplicationName("FinAI QA").build()
        fun books(): Set<String> {
            val ids = mutableSetOf<String>(); var token: String? = null
            do { val page = drive.files().list().setQ("trashed=false and mimeType='application/vnd.google-apps.spreadsheet'")
                .setPageToken(token).setFields("nextPageToken,files(id)").execute()
                ids += page.files.orEmpty().map { it.id }; token = page.nextPageToken
            } while (token != null)
            return ids
        }
        fun read(): Spreadsheet = sheets.spreadsheets().get(id).setIncludeGridData(true)
            .setFields("spreadsheetId,properties,developerMetadata,sheets(properties,basicFilter,charts,slicers,merges,conditionalFormats,data(startRow,startColumn,columnMetadata,rowMetadata,rowData(values(userEnteredValue,userEnteredFormat,effectiveValue,formattedValue,note))))")
            .execute()
        fun entries(book: Spreadsheet): Map<String,String> = buildMap {
            book.sheets.orEmpty().forEach { sheet -> sheet.data.orEmpty().forEach { grid -> grid.rowData.orEmpty().forEachIndexed { row,data ->
                data.getValues().orEmpty().forEachIndexed { column,cell -> if (cell.userEnteredValue != null || cell.note != null) {
                    put("${sheet.properties.sheetId}:${row+(grid.startRow ?: 0)}:${column+(grid.startColumn ?: 0)}",
                        "${cell.userEnteredValue}|${cell.note}")
                } }
            } } }
        }
        fun dataDigest(book: Spreadsheet): String {
            val hash = java.security.MessageDigest.getInstance("SHA-256")
            book.sheets.orEmpty().forEach { sheet -> sheet.data.orEmpty().forEach { grid ->
                hash.update("${sheet.properties.sheetId}:${grid.startRow}:${grid.startColumn}".toByteArray())
                grid.rowData.orEmpty().forEach { row -> row.getValues().orEmpty().forEach { hash.update("${it.userEnteredValue}|${it.userEnteredFormat}|${it.note}|${it.dataValidation}|${it.textFormatRuns}".toByteArray()) } }
                grid.rowMetadata.orEmpty().forEach { hash.update(it.toString().toByteArray()) }
                grid.columnMetadata.orEmpty().forEach { hash.update(it.toString().toByteArray()) }
            } }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
        val before = read()
        val beforeValues = entries(before)
        val verifyCaptureCleanup = InstrumentationRegistry.getArguments().getString("verifyCaptureCleanup") == "true"
        if (verifyCaptureCleanup) {
            val original = GsonFactory.getDefaultInstance().fromString(File(context.filesDir,"sheets-appearance-book.json").readText(),Spreadsheet::class.java)
            assertEquals("All original sheet identities must be retained without the empty capture artifact",
                original.sheets.map { it.properties.sheetId to it.properties.title },before.sheets.map { it.properties.sheetId to it.properties.title })
            assertTrue("Every original value and note must remain present after capture cleanup",beforeValues.entries.containsAll(entries(original).entries))
        }
        val beforeBooks = books()
        val report = JSONObject().put("bookId",id).put("valuesBefore",beforeValues.size)
        val reportFile = File(context.filesDir,"sheets-appearance-validation.json")
        reportFile.writeText(report.toString(2))
        before.sheets.forEach { it.data = null }
        app.sheets().ensureAppearance(account,id, reset = InstrumentationRegistry.getArguments().getString("resetTestAppearance") == "true")
        assertNull("Appearance error",app.sheets().appearance.value.error)
        val applied = read()
        assertTrue("All existing values and notes must remain unchanged",entries(applied).entries.containsAll(beforeValues.entries))
        val marker = applied.developerMetadata.orEmpty().single { it.metadataKey == "finaiAppearance" }
        assertTrue(marker.metadataValue.startsWith("1:"))
        val chartId = marker.metadataValue.split(":")[1].toInt()
        assertEquals(1,applied.sheets.orEmpty().flatMap { it.charts.orEmpty() }.count { it.chartId == chartId })
        assertEquals(before.sheets.map { it.properties.sheetId to it.properties.title },applied.sheets.map { it.properties.sheetId to it.properties.title })
        before.sheets.forEach { previous ->
            val current = applied.sheets.single { it.properties.sheetId == previous.properties.sheetId }
            if (previous.basicFilter != null) assertEquals(previous.basicFilter,current.basicFilter)
            previous.charts.orEmpty().filter { it.chartId != chartId }.forEach { chart -> assertEquals(chart,current.charts.orEmpty().single { it.chartId == chart.chartId }) }
        }
        assertTrue(File(context.noBackupFilesDir,"sheets-recovery").listFiles().orEmpty().any { it.extension == "gz" })
        val appliedValues = entries(applied)
        val appliedDataHash = dataDigest(applied)
        applied.sheets.forEach { it.data = null }
        app.sheets().ensureAppearance(account,id)
        assertNull(app.sheets().appearance.value.error)
        val repeated = read()
        assertEquals(appliedValues,entries(repeated))
        assertEquals(applied.sheets.map { it.charts },repeated.sheets.map { it.charts })
        assertEquals(appliedDataHash,dataDigest(repeated))
        assertEquals(beforeBooks,books())
        assertEquals(id,app.sync().getStoredId(account))
        val errors = repeated.sheets.orEmpty().flatMap { it.data.orEmpty() }.flatMap { it.rowData.orEmpty() }.flatMap { it.getValues().orEmpty() }.count { it.effectiveValue?.errorValue != null }
        assertEquals("No formula errors",0,errors)
        val percentFormatter = java.text.NumberFormat.getNumberInstance(java.util.Locale.forLanguageTag(repeated.properties.locale.replace('_','-'))).apply {
            minimumFractionDigits=2; maximumFractionDigits=2; isGroupingUsed=false
        }
        val percentages = repeated.sheets.orEmpty().flatMap { it.data.orEmpty() }.flatMap { it.rowData.orEmpty() }.flatMap { it.getValues().orEmpty() }
            .filter { it.userEnteredFormat?.numberFormat?.pattern == "0.00\" %\"" && it.effectiveValue?.numberValue != null }
        assertTrue("Real stored percentages are available for visual validation",percentages.isNotEmpty())
        percentages.forEach { assertEquals("Percentage must retain its numeric value and render without a dangling separator",
            percentFormatter.format(it.effectiveValue.numberValue)+" %",it.formattedValue) }
        report.put("valuesPreserved",true).put("notesFiltersPersonalChartsPreserved",true).put("sameWorkbook",true)
            .put("workbooksBefore",beforeBooks.size).put("workbooksAfter",books().size).put("ownedChartId",chartId)
            .put("repeatPreservesCustomizations",true).put("formulaErrors",errors).put("nativePercentFormatsVerified",percentages.size)
            .put("originalSheetCount",before.sheets.size).put("captureCleanupVerified",verifyCaptureCleanup)
        reportFile.writeText(report.toString(2))
        // Inspect native styles, values and formulas without exposing credentials.
        repeated.sheets.forEach { sheet -> sheet.data.orEmpty().forEach { grid -> grid.rowData = grid.rowData.orEmpty().take(20) } }
        File(context.filesDir,"sheets-appearance-book.json").writeText(GsonFactory.getDefaultInstance().toString(repeated))
    }
}
