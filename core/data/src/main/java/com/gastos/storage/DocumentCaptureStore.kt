package com.gastos.storage

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import com.gastos.data.local.entity.*
import com.gastos.domain.model.*
import com.gastos.local.database.AppDatabase
import com.gastos.repository.impl.DocumentGuard
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface CaptureStart {
    data class Draft(val value: DocumentDraftEntity, val resumed: Boolean) : CaptureStart
    data class Duplicate(val records: List<DocumentIdentity>) : CaptureStart
}

sealed interface CaptureSave {
    data class Saved(val invoice: Invoice? = null, val income: Income? = null) : CaptureSave
    data class Review(val issues: List<ReviewIssue>) : CaptureSave
    data class Duplicate(val matches: List<DuplicateMatch>) : CaptureSave
    data object MissingDraft : CaptureSave
}

@Singleton
class DocumentCaptureStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val images: InvoiceImageStorage
) {
    private val directory: File = File(context.filesDir, "document_drafts")
    private val guard: DocumentGuard = DocumentGuard(database)
    fun observe(): Flow<List<DocumentDraftEntity>> = database.documentDraftDao().observe()
    suspend fun get(uuid: String): DocumentDraftEntity? = database.documentDraftDao().get(uuid)

    suspend fun start(source: Uri): CaptureStart = withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs())
        val uuid: String = UUID.randomUUID().toString()
        val extension: String = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(context.contentResolver.getType(source))
            ?.takeIf { it in setOf("jpg", "jpeg", "png", "webp", "heic", "heif") } ?: "jpg"
        val file: File = File(directory, "$uuid.$extension")
        try {
            val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(source).use { stream ->
                DigestInputStream(requireNotNull(stream), digest).use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            }
            val hash: String = digest.digest().joinToString("") { "%02x".format(it) }
            val uri: String = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
            val result: CaptureStart = database.withTransaction {
                val matches: List<DocumentIdentity> = guard.findHash(hash)
                if (matches.isNotEmpty()) return@withTransaction CaptureStart.Duplicate(matches)
                val previous: DocumentDraftEntity? = database.documentDraftDao().findHash(hash)
                if (previous != null) return@withTransaction CaptureStart.Draft(previous, true)
                val draft: DocumentDraftEntity = DocumentDraftEntity(uuid, hash, uri)
                database.documentDraftDao().insert(draft)
                CaptureStart.Draft(draft, false)
            }
            if (result !is CaptureStart.Draft || result.resumed) file.delete()
            result
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    suspend fun update(uuid: String, evidence: DocumentEvidence?, error: String? = null) {
        database.withTransaction {
            val current: DocumentDraftEntity = get(uuid) ?: return@withTransaction
            database.documentDraftDao().update(current.copy(evidenceJson = evidence?.let(DocumentEvidenceCodec::encode) ?: current.evidenceJson,
                status = if (error != null) "ERROR" else "REVIEW", error = error))
        }
    }

    suspend fun save(uuid: String, evidence: DocumentEvidence): CaptureSave = images.mutationMutex.withLock { saveLocked(uuid, evidence) }

    private suspend fun saveLocked(uuid: String, evidence: DocumentEvidence): CaptureSave = withContext(Dispatchers.IO) {
        val draft: DocumentDraftEntity = get(uuid) ?: return@withContext CaptureSave.MissingDraft
        val issues: List<ReviewIssue> = DocumentValidator.validate(evidence).issues
        if (issues.isNotEmpty()) return@withContext CaptureSave.Review(issues)
        // Original draft survives interruptions and restore directory swaps.
        val promoted: Uri = images.persist(Uri.parse(draft.imageUri))
        var committed: Boolean = false
        try {
            val result: CaptureSave = database.withTransaction {
                val current: DocumentDraftEntity = get(uuid) ?: return@withTransaction CaptureSave.MissingDraft
                val stored: DocumentEvidence = evidence.copy(sourceSha256 = current.sourceSha256, fieldEdits = emptyMap())
                if (stored.document.kind == "nomina" || stored.document.kind == "factura_emitida") {
                    val income: Income = if (stored.document.kind == "nomina") stored.toPayroll(uuid, promoted.toString())
                        else stored.toInvoice(uuid, promoted.toString()).first.toIncome()
                    val matches: List<DuplicateMatch> = DocumentDuplicates.blocking(income.documentIdentity(), guard.find(income.documentIdentity()))
                    if (matches.isNotEmpty()) return@withTransaction CaptureSave.Duplicate(matches)
                    val id: Long = database.incomeDao().insertIncomeEntity(income.toEntity())
                    database.documentDraftDao().delete(uuid)
                    CaptureSave.Saved(income = income.copy(id = id))
                } else {
                    val (invoice: Invoice, products: List<Product>) = stored.toInvoice(uuid, promoted.toString())
                    val matches: List<DuplicateMatch> = DocumentDuplicates.blocking(invoice.documentIdentity(), guard.find(invoice.documentIdentity()))
                    if (matches.isNotEmpty()) return@withTransaction CaptureSave.Duplicate(matches)
                    val id: Long = database.invoiceDao().insertInvoice(invoice.toEntity())
                    database.productDao().insertProducts(products.map { it.copy(invoiceId = id).toEntity() })
                    database.documentDraftDao().delete(uuid)
                    CaptureSave.Saved(invoice = invoice.copy(id = id))
                }
            }
            committed = result is CaptureSave.Saved
            if (committed) deleteDraftPhoto(draft.imageUri)
            result
        } finally {
            // If cancellation occurs after commit, re-check durable state before removing a photo.
            if (!committed) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                val saved: Boolean = database.invoiceDao().documentRecords().any { it.documentUuid == uuid } ||
                    database.incomeDao().documentRecords().any { it.documentUuid == uuid }
                if (!saved) images.delete(promoted.toString())
            }
        }
    }

    suspend fun discard(uuid: String) = withContext(Dispatchers.IO) {
        val draft: DocumentDraftEntity? = get(uuid)
        database.documentDraftDao().delete(uuid)
        draft?.let { deleteDraftPhoto(it.imageUri) }
        Unit
    }

    private fun deleteDraftPhoto(uri: String) {
        val name: String = Uri.parse(uri).lastPathSegment ?: return
        val file: File = File(directory, name)
        if (file.parentFile?.canonicalFile == directory.canonicalFile) file.delete()
    }

    /** Hash legacy originals without updating any user's financial or remote metadata. */
    suspend fun indexExistingImages() = withContext(Dispatchers.IO) {
        database.invoiceDao().documentRecords().filter { it.documentKey == null }.forEach { record ->
            val key: String = record.toDomain().documentIdentity().key ?: return@forEach
            database.openHelper.writableDatabase.execSQL("UPDATE invoices SET documentKey = ? WHERE documentUuid = ? AND numeroFactura IS ? AND documentKey IS NULL",
                arrayOf(key, record.documentUuid, record.numeroFactura))
        }
        database.invoiceDao().documentRecords().filter { it.sourceSha256 == null }.forEach { record ->
            val hash: String = hashLocal(record.imagenUri) ?: return@forEach
            val invoice: Invoice = record.toDomain()
            val evidence: DocumentEvidence = (invoice.evidence ?: DocumentEvidence(ScannedDocument())).copy(sourceSha256 = hash)
            database.openHelper.writableDatabase.execSQL("UPDATE invoices SET evidenceJson = ?, sourceSha256 = ? WHERE documentUuid = ? AND imagenUri IS ? AND sourceSha256 IS NULL AND updatedAt = ? AND evidenceJson IS ?",
                arrayOf<Any?>(DocumentEvidenceCodec.encode(evidence), hash, record.documentUuid, record.imagenUri, record.updatedAt, record.evidenceJson))
        }
        database.incomeDao().documentRecords().filter { it.sourceSha256 == null }.forEach { record ->
            val hash: String = hashLocal(record.imagenUri) ?: return@forEach
            val income: Income = record.toDomain()
            val evidence: DocumentEvidence = (income.evidence ?: DocumentEvidence(ScannedDocument())).copy(sourceSha256 = hash)
            database.openHelper.writableDatabase.execSQL("UPDATE incomes SET evidenceJson = ?, sourceSha256 = ? WHERE documentUuid = ? AND imagenUri IS ? AND sourceSha256 IS NULL AND updatedAt = ? AND evidenceJson IS ?",
                arrayOf<Any?>(DocumentEvidenceCodec.encode(evidence), hash, record.documentUuid, record.imagenUri, record.updatedAt, record.evidenceJson))
        }
    }

    private fun hashLocal(uri: String?): String? {
        val file: File = images.managedFile(uri) ?: return null
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        return runCatching {
            file.inputStream().use { stream -> DigestInputStream(stream, digest).use { input ->
                val buffer: ByteArray = ByteArray(8192)
                while (input.read(buffer) >= 0) { /* digest updates during read */ }
            } }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }
}
