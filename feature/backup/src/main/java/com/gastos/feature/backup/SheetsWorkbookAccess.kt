package com.gastos.feature.backup

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File

internal object SheetsWorkbookAccess {
    fun read(drive: Drive, id: String): File {
        val file: File = try {
            drive.files().get(id).setFields("id,mimeType,trashed,appProperties").execute()
        } catch (error: GoogleJsonResponseException) {
            if (error.statusCode == 404) throw IllegalStateException("SHEETS_LINK_UNAVAILABLE", error)
            throw error
        }
        requireAvailable(file)
        return file
    }

    fun requireAvailable(file: File) {
        check(file.trashed != true) { "SHEETS_LINK_TRASHED" }
        check(file.mimeType == "application/vnd.google-apps.spreadsheet") { "SHEETS_LINK_UNAVAILABLE" }
    }
}
