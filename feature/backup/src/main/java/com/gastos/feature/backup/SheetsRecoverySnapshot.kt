package com.gastos.feature.backup

import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.model.Spreadsheet
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Private, verified pre-migration snapshot. It does not create a second Google workbook. */
internal object SheetsRecoverySnapshot {
    // Computed values and effective formats are reproducible, not recovery data. Omitting them
    // avoids materializing a second style object for every empty formatted cell on Android.
    const val FIELDS = "spreadsheetId,spreadsheetUrl,properties,namedRanges,developerMetadata,dataSources,dataSourceSchedules," +
        "sheets(properties,basicFilter,filterViews,charts,slicers,merges,conditionalFormats,bandedRanges,protectedRanges," +
        "developerMetadata,rowGroups,columnGroups,tables,data(startRow,startColumn,rowMetadata,columnMetadata," +
        "rowData(values(userEnteredValue,userEnteredFormat,note,dataValidation,textFormatRuns,pivotTable,dataSourceTable,dataSourceFormula))))"
    fun save(directory: File, account: String, workbook: Spreadsheet, protectFileName: String? = null): File {
        check(directory.isDirectory || directory.mkdirs()) { "SHEETS_BACKUP_UNVERIFIED" }
        val identity: String = MessageDigest.getInstance("SHA-256").digest("$account:${workbook.spreadsheetId}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val savedAt = maxOf(System.currentTimeMillis(), (list(directory, account, requireNotNull(workbook.spreadsheetId)).firstOrNull()?.lastModified() ?: 0L) + 1L)
        val target = File(directory, "$identity-$savedAt-${java.util.UUID.randomUUID()}.json.gz")
        val temporary = File(directory, "$identity.pending")
        val json: String = GsonFactory.getDefaultInstance().toString(workbook)
        try {
            FileOutputStream(temporary).use { file ->
                val compressed = GZIPOutputStream(file)
                compressed.write(json.toByteArray(Charsets.UTF_8))
                compressed.finish()
                compressed.flush()
                file.fd.sync()
            }
            check(read(temporary) == workbook) { "SHEETS_BACKUP_UNVERIFIED" }
            check(temporary.renameTo(target)) { "SHEETS_BACKUP_UNVERIFIED" }
            check(target.setLastModified(savedAt)) { "SHEETS_BACKUP_UNVERIFIED" }
            val copies = list(directory, account, requireNotNull(workbook.spreadsheetId))
            val kept = (copies.filter { it.name == protectFileName } + copies).distinct().take(3).toSet()
            copies.filter { it !in kept }.forEach { check(it.delete()) { "SHEETS_BACKUP_UNVERIFIED" } }
            return target
        } finally { temporary.delete() }
    }

    fun list(directory: File, account: String, workbookId: String): List<File> {
        val identity = MessageDigest.getInstance("SHA-256").digest("$account:$workbookId".toByteArray()).joinToString("") { "%02x".format(it) }
        return directory.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json.gz") &&
            (it.name == "$identity.json.gz" || it.name.startsWith("$identity-")) }.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
    }

    fun read(file: File): Spreadsheet = GZIPInputStream(file.inputStream()).use {
        GsonFactory.getDefaultInstance().fromInputStream(it, Spreadsheet::class.java)
    }
}
