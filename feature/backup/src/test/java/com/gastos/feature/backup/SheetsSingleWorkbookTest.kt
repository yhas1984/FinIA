package com.gastos.feature.backup

import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import com.google.api.services.sheets.v4.model.*
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SheetsSingleWorkbookTest {
    @Test fun `trashed workbook cannot be reported as synchronized or silently replaced`() {
        val drive = mockk<Drive>(relaxed=true)
        val files = drive.files()
        every { files.get("linked").setFields("id,mimeType,trashed,appProperties").execute() } returns
            DriveFile().setId("linked").setTrashed(true).setMimeType("application/vnd.google-apps.spreadsheet")
        assertEquals("SHEETS_LINK_TRASHED",runCatching { SheetsWorkbookAccess.read(drive,"linked") }.exceptionOrNull()?.message)
        verify(exactly=0) { files.create(any()) }
        verify(exactly=0) { files.copy(any(),any()) }
    }
    @Test fun `creation marks the file before any population and records the attempt durably`() {
        val drive = mockk<Drive>(relaxed=true)
        val body = slot<DriveFile>()
        var durable: String? = null
        every { drive.files().create(capture(body)).setFields("id").execute() } answers {
            assertNotNull(durable)
            assertEquals(durable,body.captured.appProperties["finaiCreationToken"])
            DriveFile().setId("only-book")
        }
        assertEquals("only-book", SheetsWorkbookCreation(drive).create(null) { durable = it })
        assertEquals("true", body.captured.appProperties["finaiSpreadsheet"])
        assertEquals("application/vnd.google-apps.spreadsheet", body.captured.mimeType)
    }

    @Test fun `timeout followed by retry cannot create a second book`() {
        val drive = mockk<Drive>(relaxed=true)
        var token: String? = null
        every { drive.files().create(any()).setFields("id").execute() } throws java.net.SocketTimeoutException()
        assertTrue(runCatching { SheetsWorkbookCreation(drive).create(token) { token = it } }.isFailure)
        assertNotNull(token)
        assertEquals("SHEETS_CREATION_PENDING",runCatching { SheetsWorkbookCreation(drive).create(token) { token = it } }.exceptionOrNull()?.message)
        verify(exactly=1) { drive.files().create(any()).setFields("id").execute() }
    }

    @Test fun `private recovery preserves formulas notes personal tabs and workbook identity`() {
        val folder = Files.createTempDirectory("sheets-recovery-test").toFile()
        try {
            val book = Spreadsheet().setSpreadsheetId("existing-book").setProperties(SpreadsheetProperties().setLocale("es_ES"))
                .setSheets(listOf(Sheet().setProperties(SheetProperties().setSheetId(1).setTitle("Personal"))
                    .setData(listOf(GridData().setRowData(listOf(RowData().setValues(listOf(CellData()
                        .setUserEnteredValue(ExtendedValue().setFormulaValue("=6*7")).setNote("User note")))))))))
            val file = SheetsRecoverySnapshot.save(folder,"account",book)
            assertEquals(book,SheetsRecoverySnapshot.read(file))
            SheetsRecoverySnapshot.save(folder,"account",book)
            assertEquals(1,folder.listFiles()!!.size)
            SheetsRecoverySnapshot.save(folder,"other-account",book)
            assertEquals(2,folder.listFiles()!!.size)
        } finally { folder.deleteRecursively() }
    }
}
