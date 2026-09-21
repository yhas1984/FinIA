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
    fun save(directory: File, account: String, workbook: Spreadsheet): File {
        check(directory.isDirectory || directory.mkdirs()) { "SHEETS_BACKUP_UNVERIFIED" }
        val identity: String = MessageDigest.getInstance("SHA-256").digest("$account:${workbook.spreadsheetId}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val target = File(directory, "$identity.json.gz")
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
            return target
        } finally { temporary.delete() }
    }

    fun read(file: File): Spreadsheet = GZIPInputStream(file.inputStream()).use {
        GsonFactory.getDefaultInstance().fromInputStream(it, Spreadsheet::class.java)
    }
}
