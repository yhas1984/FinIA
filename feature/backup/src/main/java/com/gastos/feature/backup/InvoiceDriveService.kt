package com.gastos.feature.backup

import android.content.Context
import android.net.Uri
import com.gastos.domain.model.*
import com.gastos.repository.InvoiceRepository
import com.gastos.repository.IncomeRepository
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

data class InvoiceDriveUploadResult(val invoice: Invoice, val uploaded: Boolean, val message: String,
    val deferred: Boolean = false, val permanent: Boolean = false)
data class IncomeDriveUploadResult(val income: Income, val uploaded: Boolean, val message: String,
    val deferred: Boolean = false, val permanent: Boolean = false)

internal data class DocumentImage(val id: Long, val uuid: String, val kind: DocumentKind, val sourceUri: String?,
    val fileId: String?, val link: String?, val pending: Boolean, val accountId: String?, val hash: String?)
internal data class ImageUpload(val metadata: DriveImageMetadata, val uploaded: Boolean, val error: String = "",
    val deferred: Boolean = false, val permanent: Boolean = false)

@Singleton
class InvoiceDriveService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sheetsExportService: SheetsExportService,
    private val invoiceRepository: InvoiceRepository,
    private val premiumStatus: PremiumStatusProvider,
    private val remoteSyncOutbox: RemoteSyncOutboxRepository,
    private val incomeRepository: IncomeRepository
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val uploadMutex = Mutex()
    private val requestMutex = Mutex()

    suspend fun upload(requested: Invoice, stillCurrent: suspend () -> Boolean = { true }): InvoiceDriveUploadResult = requestMutex.withLock {
        val invoice = invoiceRepository.getInvoiceById(requested.id)?.takeIf { it.documentUuid == requested.documentUuid }
            ?: return@withLock InvoiceDriveUploadResult(requested, false, "CANCELLED", permanent = true)
        val ref = DocumentImage(invoice.id, invoice.documentUuid, DocumentKind.EXPENSE, invoice.imagenUri,
            invoice.driveFileId, invoice.driveWebViewLink, invoice.driveUploadPending, invoice.driveAccountId, invoice.driveContentHash)
        val result = uploadDocument(ref, stillCurrent) { metadata ->
            invoiceRepository.updateImageSync(invoice.id, invoice.documentUuid, invoice.imagenUri, metadata)
        }
        return@withLock InvoiceDriveUploadResult(invoice.copy(driveFileId = result.metadata.fileId,
            driveWebViewLink = result.metadata.webViewLink, driveUploadPending = result.metadata.pending,
            driveAccountId = result.metadata.accountId, driveContentHash = result.metadata.contentHash,
            driveSyncError = result.metadata.error), result.uploaded, result.error, result.deferred, result.permanent)
    }

    suspend fun upload(requested: Income, stillCurrent: suspend () -> Boolean = { true }): IncomeDriveUploadResult = requestMutex.withLock {
        val income = incomeRepository.getIncomeById(requested.id)?.takeIf { it.documentUuid == requested.documentUuid }
            ?: return@withLock IncomeDriveUploadResult(requested, false, "CANCELLED", permanent = true)
        val ref = DocumentImage(income.id, income.documentUuid, DocumentKind.INCOME, income.imagenUri,
            income.driveFileId, income.driveWebViewLink, income.driveUploadPending, income.driveAccountId, income.driveContentHash)
        val result = uploadDocument(ref, stillCurrent) { metadata ->
            incomeRepository.updateImageSync(income.id, income.documentUuid, income.imagenUri, metadata)
        }
        return@withLock IncomeDriveUploadResult(income.copy(driveFileId = result.metadata.fileId,
            driveWebViewLink = result.metadata.webViewLink, driveUploadPending = result.metadata.pending,
            driveAccountId = result.metadata.accountId, driveContentHash = result.metadata.contentHash,
            driveSyncError = result.metadata.error), result.uploaded, result.error, result.deferred, result.permanent)
    }

    private suspend fun uploadDocument(ref: DocumentImage, stillCurrent: suspend () -> Boolean,
        save: suspend (DriveImageMetadata) -> Boolean): ImageUpload = withContext(Dispatchers.IO) {
        uploadMutex.withLock {
            var metadata = DriveImageMetadata(ref.fileId, ref.link, true, ref.accountId, ref.hash)
            suspend fun fail(code: String, deferred: Boolean = false, permanent: Boolean = false): ImageUpload {
                metadata = metadata.copy(error = code)
                if (stillCurrent()) save(metadata)
                return ImageUpload(metadata, false, code, deferred, permanent)
            }
            if (!stillCurrent()) return@withLock ImageUpload(metadata, false, "CANCELLED", permanent = true)
            if (!premiumStatus.isPremium.value) return@withLock fail("PREMIUM_REQUIRED", deferred = true)
            val account = sheetsExportService.getLastSignedInAccount()
                ?: return@withLock fail("AUTH_REQUIRED", deferred = true)
            val accountId = account.id ?: account.email.orEmpty()
            if (ref.accountId != null && ref.accountId != accountId) return@withLock fail("WRONG_ACCOUNT", deferred = true)
            val selected = account.account ?: return@withLock fail("AUTH_REQUIRED", deferred = true)
            try {
                if (ref.sourceUri == null) {
                    // A data-only restore may contain a reservation whose upload succeeded
                    // before the HTTP response was lost. Confirm it without needing the original.
                    val reserved = ref.fileId?.takeIf { ref.hash != null }?.let { getFileOrNull(createDriveService(selected), it) }
                    if (reserved != null && ref.hash != null) {
                        if (!matches(reserved, ref, ref.hash)) return@withLock fail("IDENTITY_REVIEW_REQUIRED", permanent = true)
                        metadata = metadata.copy(pending = false, accountId = accountId,
                            webViewLink = reserved.webViewLink ?: "https://drive.google.com/file/d/${reserved.id}/view", error = null)
                        if (!stillCurrent() || !save(metadata)) return@withLock ImageUpload(metadata, false, "CANCELLED", permanent = true)
                        return@withLock ImageUpload(metadata, true)
                    }
                    return@withLock fail("MISSING_SOURCE", permanent = true)
                }
                val uri = Uri.parse(ref.sourceUri)
                val digest = java.security.MessageDigest.getInstance("MD5")
                val input = context.contentResolver.openInputStream(uri)
                    ?: return@withLock fail("MISSING_SOURCE", permanent = true)
                input.use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                val drive = createDriveService(selected)
                var file = ref.fileId?.let { getFileOrNull(drive, it) }
                if (file != null && !matches(file, ref, hash)) return@withLock fail("IDENTITY_REVIEW_REQUIRED", permanent = true)
                if (file == null && ref.fileId != null && !ref.pending) return@withLock fail("REMOTE_FILE_MISSING", permanent = true)
                if (file == null && ref.fileId == null) {
                    val found = runInterruptible { drive.files().list().setSpaces("drive")
                        .setQ("trashed=false and appProperties has { key='finaiUuid' and value='${queryValue(ref.uuid)}' } " +
                            "and appProperties has { key='finaiKind' and value='${ref.kind.name}' }")
                        .setFields("files($FILE_FIELDS)").execute().files.orEmpty() }
                    if (found.size > 1) return@withLock fail("IDENTITY_REVIEW_REQUIRED", permanent = true)
                    file = found.singleOrNull()
                    if (file != null && !matches(file, ref, hash)) return@withLock fail("IDENTITY_REVIEW_REQUIRED", permanent = true)
                }
                val fileId = file?.id ?: ref.fileId ?: runInterruptible {
                    drive.files().generateIds().setCount(1).setSpace("drive").execute().ids.single()
                }
                metadata = metadata.copy(fileId = fileId, accountId = accountId, contentHash = hash, error = null)
                // Durable reservation precedes create, so a lost HTTP response reuses the same file ID.
                if (!stillCurrent() || !save(metadata)) return@withLock ImageUpload(metadata, false, "CANCELLED", permanent = true)
                val properties = mapOf("finaiUuid" to ref.uuid, "finaiKind" to ref.kind.name, "finaiContentMd5" to hash)
                if (file == null) {
                    val folderId = ensureFinAiFolder(drive, accountId)
                    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val fileMetadata = DriveFile().setId(fileId).setName("${ref.kind.name.lowercase()}_${ref.uuid}")
                        .setParents(listOf(folderId)).setAppProperties(properties)
                    if (!stillCurrent()) return@withLock ImageUpload(metadata, false, "CANCELLED", permanent = true)
                    try {
                        context.contentResolver.openInputStream(uri).use { stream ->
                            requireNotNull(stream)
                            file = runInterruptible { drive.files().create(fileMetadata, InputStreamContent(mime, stream))
                                .setFields(FILE_FIELDS).execute() }
                        }
                    } catch (error: GoogleJsonResponseException) {
                        if (error.statusCode != 409) throw error
                        file = getFileOrNull(drive, fileId)
                    }
                } else if (file?.appProperties?.get("finaiUuid") == null) {
                    // A legacy file is adopted only after checking its explicit reference and checksum.
                    file = runInterruptible { drive.files().update(fileId, DriveFile().setAppProperties(properties))
                        .setFields(FILE_FIELDS).execute() }
                }
                val confirmed = file ?: return@withLock fail("REMOTE_FILE_MISSING", permanent = true)
                if (!matches(confirmed, ref, hash)) return@withLock fail("IDENTITY_REVIEW_REQUIRED", permanent = true)
                metadata = metadata.copy(webViewLink = confirmed.webViewLink ?: "https://drive.google.com/file/d/$fileId/view", pending = false)
                if (!stillCurrent() || !save(metadata)) return@withLock ImageUpload(metadata, false, "CANCELLED", permanent = true)
                ImageUpload(metadata, true)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: java.io.FileNotFoundException) { fail("MISSING_SOURCE", permanent = true)
            } catch (error: Exception) {
                val classified = GoogleApiErrorClassifier.classify(error, GoogleApiErrorContext("Drive", "OFFLINE", "SERVER_UNAVAILABLE", "PERMISSION_OR_QUOTA", "UPLOAD_FAILED"))
                val deferred = classified.category in setOf(GoogleApiErrorCategory.NETWORK, GoogleApiErrorCategory.AUTH_RECOVERABLE,
                    GoogleApiErrorCategory.AUTH_PERMANENT, GoogleApiErrorCategory.PLAY_SERVICES)
                fail(if (deferred && classified.category != GoogleApiErrorCategory.NETWORK) "AUTH_REQUIRED" else classified.message,
                    deferred, !deferred && !classified.shouldRetry)
            }
        }
    }

    private fun matches(file: DriveFile, ref: DocumentImage, hash: String): Boolean {
        if (file.trashed == true || file.md5Checksum != hash) return false
        val uuid = file.appProperties?.get("finaiUuid")
        return if (uuid != null) uuid == ref.uuid && file.appProperties?.get("finaiKind") == ref.kind.name
        else ref.kind == DocumentKind.EXPENSE && file.id == ref.fileId &&
            file.appProperties?.get("finaiInvoiceId") == ref.id.toString()
    }

    private suspend fun getFileOrNull(drive: Drive, id: String): DriveFile? = try {
        runInterruptible { drive.files().get(id).setFields(FILE_FIELDS).execute() }
    } catch (error: GoogleJsonResponseException) { if (error.statusCode == 404) null else throw error }

    suspend fun enqueueDelete(invoice: Invoice, consent: Boolean = false) {
        remoteSyncOutbox.enqueue(RemoteSyncTarget.INVOICE_DRIVE, invoice.id, RemoteSyncAction.DELETE,
            invoice.driveFileId, invoice.documentUuid, invoice.driveAccountId, consent)
    }
    suspend fun enqueueDelete(income: Income, consent: Boolean = false) {
        remoteSyncOutbox.enqueue(RemoteSyncTarget.INCOME_DRIVE, income.id, RemoteSyncAction.DELETE,
            income.driveFileId, income.documentUuid, income.driveAccountId, consent)
    }

    suspend fun delete(remoteFileId: String, accountId: String? = null): Boolean = withContext(Dispatchers.IO) {
        val account = sheetsExportService.getLastSignedInAccount() ?: return@withContext false
        if (accountId == null || accountId != (account.id ?: account.email.orEmpty())) return@withContext false
        val selected = account.account ?: return@withContext false
        try {
            runInterruptible { createDriveService(selected).files().delete(remoteFileId).execute() }; true
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: GoogleJsonResponseException) { if (error.statusCode == 404) true else throw error }
    }

    fun activeAccountId(): String? = sheetsExportService.getLastSignedInAccount()?.let { it.id ?: it.email }

    suspend fun downloadImage(fileId: String, expectedAccountId: String?, destination: java.io.OutputStream) = withContext(Dispatchers.IO) {
        val account = sheetsExportService.getLastSignedInAccount() ?: throw ImageAccessException("AUTH_REQUIRED")
        if (expectedAccountId != null && expectedAccountId != (account.id ?: account.email)) throw ImageAccessException("WRONG_ACCOUNT")
        val selected = account.account ?: throw ImageAccessException("AUTH_REQUIRED")
        try {
            runInterruptible { createDriveService(selected).files().get(fileId).executeMediaAndDownloadTo(destination) }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: GoogleJsonResponseException) {
            throw ImageAccessException(when (error.statusCode) { 404 -> "REMOTE_FILE_MISSING"; 401, 403 -> "PERMISSION_REQUIRED"; else -> "SERVER_UNAVAILABLE" })
        } catch (error: ImageAccessException) { throw error
        } catch (_: java.io.IOException) { throw ImageAccessException("OFFLINE") }
    }

    fun clearAccountCache() { prefs.edit().clear().apply() }
    private fun queryValue(value: String) = value.replace("\\", "\\\\").replace("'", "\\'")

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

    private suspend fun ensureFinAiFolder(drive: Drive, accountKey: String): String {
        val preferenceKey = "$KEY_FOLDER_ID_PREFIX${accountKey.hashCode()}"
        val storedId = prefs.getString(preferenceKey, null)
        if (!storedId.isNullOrBlank()) {
            try {
                val stored = runInterruptible {
                    drive.files().get(storedId)
                        .setFields("id,mimeType,trashed")
                        .execute()
                }
                if (stored.mimeType == FOLDER_MIME_TYPE && stored.trashed != true) return stored.id
            } catch (error: GoogleJsonResponseException) {
                if (error.statusCode != 404) throw error
            }
            prefs.edit().remove(preferenceKey).apply()
        }

        val existing = runInterruptible {
            drive.files().list()
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
        }

        val folderId = existing?.id ?: runInterruptible {
            drive.files().create(
                DriveFile()
                    .setName(FOLDER_NAME)
                    .setMimeType(FOLDER_MIME_TYPE)
                    .setParents(Collections.singletonList("root"))
                    .setAppProperties(mapOf("finaiRoot" to "true"))
            )
                .setFields("id")
                .execute()
                .id
        }

        prefs.edit().putString(preferenceKey, folderId).apply()
        return folderId
    }

    companion object {
        private const val PREFS_NAME = "finai_drive_sync"
        private const val KEY_FOLDER_ID_PREFIX = "folder_id_"
        private const val FOLDER_NAME = "FinAI"
        private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private const val FILE_FIELDS = "id,name,webViewLink,appProperties,md5Checksum,trashed"
    }
}

class ImageAccessException(val code: String) : java.io.IOException(code)
