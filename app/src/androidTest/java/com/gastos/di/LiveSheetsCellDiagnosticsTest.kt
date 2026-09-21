@file:Suppress("DEPRECATION")
package com.gastos.di

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in inspection, with a separate flag to remove only our known locale-test probes. */
@RunWith(AndroidJUnit4::class)
class LiveSheetsCellDiagnosticsTest {
    @Test fun inspectErrors(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("inspectSheetCells") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".dev"))
        val app = EntryPointAccessors.fromApplication(context,LiveAccountTestEntryPoint::class.java)
        val account = requireNotNull(app.sheets().getLastSignedInAccount())
        val book = app.sync().getStoredId(account)
        check(book.isNotBlank())
        val credential = GoogleAccountCredential.usingOAuth2(context,listOf(DriveScopes.DRIVE_FILE)).setSelectedAccount(account.account)
        val sheets = Sheets.Builder(NetHttpTransport(),GsonFactory.getDefaultInstance(),credential).setApplicationName("FinAI QA").build()
        fun read(): Spreadsheet = sheets.spreadsheets().get(book).setIncludeGridData(true)
            .setFields("properties(locale),sheets(properties(title,sheetId),data(startRow,startColumn,rowData(values(userEnteredValue,effectiveValue))))").execute()
        var workbook = read()
        val removed = JSONArray()
        if (InstrumentationRegistry.getArguments().getString("removeLocaleTestProbes") == "true") {
            val before = workbook
            val requests = mutableListOf<Request>()
            val cleaned = mutableSetOf<Triple<Int,Int,Int>>()
            for (sheet in workbook.sheets.orEmpty().filter { it.properties.title == "Análisis personal" }) {
                for (grid in sheet.data.orEmpty()) grid.rowData.orEmpty().forEachIndexed { r, row ->
                    row.getValues().orEmpty().forEachIndexed { c, cell ->
                        val rowIndex = (grid.startRow ?: 0) + r
                        val columnIndex = (grid.startColumn ?: 0) + c
                        val formula = cell.userEnteredValue?.formulaValue
                        if (columnIndex == 0 && rowIndex in 0..2 && formula in setOf("=IF(1=1,2,3)","=IF(1=1;2;3)")) {
                            cleaned += Triple(sheet.properties.sheetId,rowIndex,columnIndex)
                            removed.put(JSONObject().put("cell","A${rowIndex+1}").put("formula",formula))
                            requests += Request().setUpdateCells(UpdateCellsRequest().setStart(GridCoordinate()
                                .setSheetId(sheet.properties.sheetId).setRowIndex(rowIndex).setColumnIndex(columnIndex))
                                .setRows(listOf(RowData().setValues(listOf(CellData())))).setFields("userEnteredValue"))
                        }
                    }
                }
            }
            if (requests.isNotEmpty()) sheets.spreadsheets().batchUpdate(book,BatchUpdateSpreadsheetRequest().setRequests(requests)).execute()
            workbook = read()
            fun entries(value: Spreadsheet): Map<Triple<Int,Int,Int>,ExtendedValue> = buildMap {
                for (sheet in value.sheets.orEmpty()) for (grid in sheet.data.orEmpty()) grid.rowData.orEmpty().forEachIndexed { r,row ->
                    row.getValues().orEmpty().forEachIndexed { c,cell -> cell.userEnteredValue?.let {
                        put(Triple(sheet.properties.sheetId,(grid.startRow ?: 0)+r,(grid.startColumn ?: 0)+c),it)
                    } }
                }
            }
            assertTrue("Unrelated workbook cells must be preserved", entries(before).filterKeys { it !in cleaned } == entries(workbook))
        }
        val errors = JSONArray()
        val counts = JSONArray()
        for (sheet in workbook.sheets.orEmpty()) {
            var formulas = 0
            for (grid in sheet.data.orEmpty()) grid.rowData.orEmpty().forEachIndexed { rowIndex, row ->
                row.getValues().orEmpty().forEachIndexed { columnIndex, cell ->
                    if (cell.userEnteredValue?.formulaValue != null) formulas++
                    cell.effectiveValue?.errorValue?.let { error ->
                        var column = (grid.startColumn ?: 0) + columnIndex + 1
                        var label = ""
                        while (column > 0) { column--; label = ('A' + column % 26) + label; column /= 26 }
                        errors.put(JSONObject().put("sheet",sheet.properties.title).put("cell",label+((grid.startRow ?: 0)+rowIndex+1))
                            .put("formula",cell.userEnteredValue?.formulaValue).put("type",error.type).put("message",error.message))
                    }
                }
            }
            counts.put(JSONObject().put("sheet",sheet.properties.title).put("formulas",formulas))
        }
        File(context.filesDir,"sheets-cell-diagnostic.json").writeText(JSONObject().put("locale",workbook.properties.locale)
            .put("errors",errors).put("sheets",counts).put("removedTestProbes",removed).toString(2))
        if (InstrumentationRegistry.getArguments().getString("removeLocaleTestProbes") == "true")
            assertTrue("The workbook must have no remaining cell errors",errors.length() == 0)
    }
}
