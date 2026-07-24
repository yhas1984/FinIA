package com.gastos.feature.backup

import android.content.Context
import android.webkit.MimeTypeMap
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.repository.InvoiceRepository
import com.gastos.repository.PremiumStatusProvider
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

data class InvoiceDriveUploadResult(
    val invoice: Invoice,
    val uploaded: Boolean,
    val message: String
)

@Singleton
class InvoiceDriveService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sheetsExportService: SheetsExportService,
    private val invoiceRepository: InvoiceRepository,
    private val premiumStatus: PremiumStatusProvider
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun upload(invoice: Invoice): InvoiceDriveUploadResult = withContext(Dispatchers.IO) {
        if (!premiumStatus.isPremium.value) {
            return@withContext InvoiceDriveUploadResult(
                invoice = invoice.copy(driveUploadPending = true),
                uploaded = false,
                message = "La copia en Google Drive requiere Premium."
            )
        }
        if (invoice.tipo != InvoiceType.GASTO || invoice.id <= 0 || invoice.imagenUri.isNullOrBlank()) {
            return@withContext InvoiceDriveUploadResult(
                invoice = invoice,
                uploaded = false,
                message = "La factura no tiene una imagen válida para subir."
            )
        }
        val account = sheetsExportService.getLastSignedInAccount()
            ?: return@withContext InvoiceDriveUploadResult(
                invoice = invoice.copy(driveUploadPending = true),
                uploaded = false,
                message = "Conecta tu cuenta Google para subir la foto a Drive."
            )

        try {
            val selectedAccount = account.account
                ?: error("La cuenta Google seleccionada no está disponible")
            val drive = createDriveService(selectedAccount)
            val folderId = ensureFinAiFolder(drive, account.id ?: account.email.orEmpty())
            val existing = findInvoiceFile(drive, folderId, invoice.id)
            val uploadedFile = existing ?: createInvoiceFile(drive, folderId, invoice)
            val updated = invoice.copy(
                driveFileId = uploadedFile.id,
                driveWebViewLink = uploadedFile.webViewLink,
                driveUploadPending = false
            )
            invoiceRepository.updateDriveMetadata(
                invoiceId = invoice.id,
                fileId = uploadedFile.id,
                webViewLink = uploadedFile.webViewLink,
                pending = false
            )
            InvoiceDriveUploadResult(
                invoice = updated,
                uploaded = true,
                message = "Foto guardada en Google Drive."
            )
        } catch (error: Exception) {
            InvoiceDriveUploadResult(
                invoice = invoice.copy(driveUploadPending = true),
                uploaded = false,
                message = friendlyMessage(error)
            )
        }
    }

    fun clearAccountCache() {
        prefs.edit().clear().apply()
    }

    private fun createDriveService(account: android.accounts.Account): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DriveScopes.DRIVE_FILE)
        ).setSelectedAccount(account)
        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("FinAI")
            .build()
    }

    private fun ensureFinAiFolder(drive: Drive, accountKey: String): String {
        val preferenceKey = "$KEY_FOLDER_ID_PREFIX${accountKey.hashCode()}"
        val storedId = prefs.getString(preferenceKey, null)
        if (!storedId.isNullOrBlank()) {
            try {
                val stored = drive.files().get(storedId)
                    .setFields("id,mimeType,trashed")
                    .execute()
                if (stored.mimeType == FOLDER_MIME_TYPE && stored.trashed != true) return stored.id
            } catch (error: GoogleJsonResponseException) {
                if (error.statusCode != 404) throw error
            }
            prefs.edit().remove(preferenceKey).apply()
        }

        val existing = drive.files().list()
            .setSpaces("drive")
            .setQ(
                "mimeType='$FOLDER_MIME_TYPE' and 'root' in parents and trashed=false " +
                    "and appProperties has { key='finaiRoot' and value='true' }"
            )
            .setFields("files(id)")
            .execute()
            .files
            .orEmpty()
            .firstOrNull()

        val folderId = existing?.id ?: drive.files().create(
            DriveFile()
                .setName(FOLDER_NAME)
                .setMimeType(FOLDER_MIME_TYPE)
                .setParents(Collections.singletonList("root"))
                .setAppProperties(mapOf("finaiRoot" to "true"))
        )
            .setFields("id")
            .execute()
            .id

        prefs.edit().putString(preferenceKey, folderId).apply()
        return folderId
    }

    private fun findInvoiceFile(drive: Drive, folderId: String, invoiceId: Long): DriveFile? =
        drive.files().list()
            .setSpaces("drive")
            .setQ(
                "'$folderId' in parents and trashed=false " +
                    "and appProperties has { key='finaiInvoiceId' and value='$invoiceId' }"
            )
            .setFields("files(id,name,webViewLink)")
            .execute()
            .files
            .orEmpty()
            .firstOrNull()

    private fun createInvoiceFile(drive: Drive, folderId: String, invoice: Invoice): DriveFile {
        val uri = android.net.Uri.parse(invoice.imagenUri)
        val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
        val extension = MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(mimeType)
            ?.lowercase()
            ?.takeIf { it in SUPPORTED_EXTENSIONS }
            ?: "jpg"
        val metadata = DriveFile()
            .setName("factura_${invoice.id}.$extension")
            .setParents(Collections.singletonList(folderId))
            .setAppProperties(mapOf("finaiInvoiceId" to invoice.id.toString()))
        val input = requireNotNull(context.contentResolver.openInputStream(uri)) {
            "No se pudo abrir la imagen local de la factura"
        }
        input.use {
            return drive.files().create(metadata, InputStreamContent(mimeType, it))
                .setFields("id,name,webViewLink")
                .execute()
        }
    }

    private fun friendlyMessage(error: Exception): String = when (error) {
        is GoogleJsonResponseException -> when (error.statusCode) {
            401 -> "La autorización de Google caducó. Vuelve a conectar la cuenta."
            403 -> "Google Drive rechazó el permiso o la cuota disponible."
            else -> "No se pudo subir la foto a Drive (${error.statusCode}). Podrás reintentarlo."
        }
        is java.io.IOException -> "Sin conexión con Google Drive. Podrás reintentar la subida."
        else -> "No se pudo subir la foto a Drive. Podrás reintentarlo."
    }

    companion object {
        private const val PREFS_NAME = "finai_drive_sync"
        private const val KEY_FOLDER_ID_PREFIX = "folder_id_"
        private const val FOLDER_NAME = "FinAI"
        private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
    }
}
