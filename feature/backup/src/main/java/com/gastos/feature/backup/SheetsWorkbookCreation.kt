package com.gastos.feature.backup

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAuthIOException
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import java.util.UUID

/** A timed-out create is an unknown outcome: discover its marker, never blindly create again. */
internal class SheetsWorkbookCreation(private val drive: Drive) {
    fun create(pendingToken: String?, recordToken: (String?) -> Unit): String {
        check(pendingToken == null) { "SHEETS_CREATION_PENDING" }
        val token: String = UUID.randomUUID().toString()
        recordToken(token)
        try {
            return requireNotNull(drive.files().create(File().setName("FinAI")
                .setMimeType("application/vnd.google-apps.spreadsheet")
                .setAppProperties(mapOf("finaiSpreadsheet" to "true", "finaiCreationToken" to token, "finaiInitializing" to "true")))
                .setFields("id").execute().id)
        } catch (error: Exception) {
            // Only definitive rejection allows another create. Network failures and cancellation keep the marker.
            if (error is GoogleAuthIOException || error is GoogleJsonResponseException && error.statusCode in setOf(400, 401, 403, 404, 429))
                recordToken(null)
            throw error
        }
    }
}
