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
import kotlinx.coroutines.ensureActive
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
    data object UnreadableAmount : CaptureSave
    data class Duplicate(val matches: List<DuplicateMatch>) : CaptureSave
    data class Conflict(val uuid: String, val isIncome: Boolean, val fields: List<String>) : CaptureSave
    data object MissingDraft : CaptureSave
}

@Singleton
class DocumentCaptureStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val images: InvoiceImageStorage,
    private val defaults: com.gastos.repository.ManualEntryDefaultsProvider? = null
) {
    private val directory: File = File(context.filesDir, "document_drafts")
    private val guard: DocumentGuard = DocumentGuard(database)
    fun observe(): Flow<List<DocumentDraftEntity>> = database.documentDraftDao().observe()
    suspend fun get(uuid: String): DocumentDraftEntity? = database.documentDraftDao().get(uuid)

    suspend fun start(source: Uri): CaptureStart = withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs())
        val uuid: String = UUID.randomUUID().toString()
        val isPdf: Boolean = context.contentResolver.openInputStream(source).use { stream ->
            val prefix = ByteArray(5); requireNotNull(stream).read(prefix); prefix.toString(Charsets.US_ASCII) == "%PDF-"
        }
        val mimeTypes = android.webkit.MimeTypeMap.getSingleton()
        val sourceMime = context.contentResolver.getType(source) ?: source.lastPathSegment
            ?.substringAfterLast('.', "")?.lowercase(java.util.Locale.ROOT)?.let(mimeTypes::getMimeTypeFromExtension)
        require(isPdf || sourceMime?.startsWith("image/") == true) { "DOCUMENT_TYPE_INVALID" }
        val extension: String = if (isPdf) "pdf" else mimeTypes.getExtensionFromMimeType(sourceMime)
            ?.takeIf { it in setOf("jpg", "jpeg", "png", "webp", "heic", "heif") } ?: "jpg"
        val file: File = File(directory, "$uuid.$extension")
        try {
            val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(source).use { stream ->
                DigestInputStream(requireNotNull(stream), digest).use { input -> file.outputStream().use { output -> val buffer: ByteArray = ByteArray(8192)
                    var total: Long = 0
                    while (true) {
                        val count: Int = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (isPdf) require(total <= PdfSandbox.MAX_BYTES) { "PDF_TOO_LARGE" }
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        output.write(buffer, 0, count)
                    } } }
            }
            if (isPdf) require(PdfSandbox.pageCount(context, file) in 1..PdfSandbox.MAX_PAGES) { "PDF_PAGE_LIMIT" }
            val hash: String = digest.digest().joinToString("") { "%02x".format(it) }
            val uri: String = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
            val result: CaptureStart = database.withTransaction {
                val matches: List<DocumentIdentity> = guard.findHash(hash)
                if (matches.isNotEmpty()) return@withTransaction CaptureStart.Duplicate(matches)
                val previous: DocumentDraftEntity? = database.documentDraftDao().findHash(hash)
                if (previous != null) return@withTransaction CaptureStart.Draft(previous, true)
                val sourceName = runCatching { context.contentResolver.query(source, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0)?.replace(Regex("[\\r\\n]"), " ")?.take(255) else null
                } }.getOrNull()
                val draft: DocumentDraftEntity = DocumentDraftEntity(uuid, hash, uri, sourceName = sourceName,
                    sourceMimeType = if (isPdf) "application/pdf" else sourceMime)
                database.documentDraftDao().insert(draft)
                CaptureStart.Draft(draft, false)
            }
            if (result !is CaptureStart.Draft || result.resumed) file.delete()
            result
        } catch (error: Exception) {
            // Cancellation can occur after Room committed the draft but before returning it.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                val durable = runCatching { get(uuid) != null }.getOrDefault(true)
                if (!durable) file.delete()
            }
            throw error
        }
    }

    suspend fun update(uuid: String, evidence: DocumentEvidence?, error: String? = null) {
        database.withTransaction {
            val current: DocumentDraftEntity = get(uuid) ?: return@withTransaction
            database.documentDraftDao().update(current.copy(evidenceJson = evidence?.let(DocumentEvidenceCodec::encode) ?: current.evidenceJson,
                status = if (error != null) "ERROR" else "EXTRACTED", error = error))
        }
    }

    suspend fun possibleWalletPayments(evidence: DocumentEvidence): List<Invoice> {
        val document = evidence.document
        if (document.kind in setOf("nomina", "factura_emitida")) return emptyList()
        val amount = DocumentExtraction.amount(document) ?: return emptyList()
        val date = DocumentValidator.parseDate(document.date) ?: return emptyList()
        return database.invoiceDao().documentRecords().filter {
            val distance = kotlin.math.abs(it.fecha - date)
            val dateMatches = if (it.origin == "BANK") distance <= 7L * 24 * 60 * 60 * 1000
                else distance < 3L * 24 * 60 * 60 * 1000
            it.origin in setOf("WALLET", "BANK") && it.imagenUri == null && it.moneda == document.currency &&
                kotlin.math.abs(it.total - amount) < 0.005 && dateMatches
        }.map(InvoiceEntity::toDomain)
    }
    suspend fun attachReceipt(uuid: String, evidence: DocumentEvidence, wallet: Invoice, keepExisting: Boolean? = null): CaptureSave =
        images.mutationMutex.withLock { saveLocked(uuid, evidence, wallet, keepExisting = keepExisting) }

    suspend fun possibleBankIncomes(evidence: DocumentEvidence): List<Income> {
        if (evidence.document.kind !in setOf("nomina", "factura_emitida")) return emptyList()
        val amount = DocumentExtraction.amount(evidence.document) ?: return emptyList()
        val date = DocumentValidator.parseDate(evidence.document.date) ?: return emptyList()
        return database.incomeDao().documentRecords().filter { it.origin == "BANK" && it.imagenUri == null &&
            it.moneda == evidence.document.currency && kotlin.math.abs(it.monto - amount) < 0.005 &&
            kotlin.math.abs(it.fecha - date) <= 7L * 24 * 60 * 60 * 1000 }.map { it.toDomain() }
    }

    suspend fun attachIncomeReceipt(uuid: String, evidence: DocumentEvidence, income: Income, keepExisting: Boolean? = null): CaptureSave =
        images.mutationMutex.withLock { saveLocked(uuid, evidence, bankIncome = income, keepExisting = keepExisting) }

    suspend fun save(uuid: String, evidence: DocumentEvidence): CaptureSave = images.mutationMutex.withLock { saveLocked(uuid, evidence) }

    private suspend fun saveLocked(uuid: String, evidence: DocumentEvidence, wallet: Invoice? = null, bankIncome: Income? = null, keepExisting: Boolean? = null): CaptureSave = withContext(Dispatchers.IO) {
        val draft: DocumentDraftEntity = get(uuid) ?: return@withContext CaptureSave.MissingDraft
        if (DocumentExtraction.amount(evidence.document) == null) return@withContext CaptureSave.UnreadableAmount
        // Original draft survives interruptions and restore directory swaps.
        val promoted: Uri = images.persist(Uri.parse(draft.imageUri))
        val currency = defaults?.manualEntryDefaults()?.currency ?: "EUR"
        var committed: Boolean = false
        try {
            val result: CaptureSave = database.withTransaction {
                val current: DocumentDraftEntity = get(uuid) ?: return@withTransaction CaptureSave.MissingDraft
                val stored: DocumentEvidence = DocumentProvenance.prepareCapture(evidence.copy(sourceSha256 = current.sourceSha256, fieldEdits = emptyMap()), currency, current.createdAt)
                if (stored.document.kind == "nomina" || stored.document.kind == "factura_emitida") {
                    val previousIncome = bankIncome?.let { candidate -> requireNotNull(database.incomeDao().getIncomeById(candidate.id)).also {
                        require(it.documentUuid == candidate.documentUuid && it.origin == "BANK" && it.imagenUri == null && it.financialRevision == candidate.financialRevision &&
                            it.moneda == stored.document.currency && DocumentExtraction.amount(stored.document)?.let { amount -> kotlin.math.abs(amount - it.monto) < 0.005 } == true) { "PAYMENT_CHANGED" }
                    } }
                    val incomeUuid = previousIncome?.documentUuid ?: uuid
                    val createdAt = previousIncome?.createdAt ?: current.createdAt
                    val extractedIncome: Income = if (stored.document.kind == "nomina") stored.toPayroll(incomeUuid, promoted.toString(), createdAt)
                        else stored.toInvoice(incomeUuid, promoted.toString(), createdAt).first.toIncome()
                    val enriched = previousIncome?.let { DocumentEnrichment.income(it.toDomain(), extractedIncome, keepExisting ?: true) }
                    if (enriched != null && enriched.conflicts.isNotEmpty() && keepExisting == null) return@withTransaction CaptureSave.Conflict(incomeUuid, true, enriched.conflicts)
                    val income: Income = CategoryCatalog(database).assign((enriched?.value ?: extractedIncome).copy(
                        id = previousIncome?.id ?: 0, financialRevision = previousIncome?.financialRevision?.plus(1) ?: 0,
                        categoria = previousIncome?.categoria ?: extractedIncome.categoria, subcategoria = previousIncome?.subcategoria ?: extractedIncome.subcategoria,
                        origin = if (previousIncome == null) "DOCUMENT" else "BANK_RECEIPT", sourceName = current.sourceName, sourceMimeType = current.sourceMimeType ?: context.contentResolver.getType(promoted)), previousIncome == null)
                    val matches: List<DuplicateMatch> = DocumentDuplicates.blocking(income.documentIdentity(), guard.find(income.documentIdentity()))
                    if (matches.isNotEmpty()) return@withTransaction CaptureSave.Duplicate(matches)
                    val id: Long = if (previousIncome == null) database.incomeDao().insertIncomeEntity(income.toEntity())
                        else { database.incomeDao().updatePreservingImageState(income.toEntity()); previousIncome.id }
                    database.chatMessageDao().insertAndTrim(createDocumentChatMessage(context, income.copy(id = id).documentIdentity()))
                    FinancialMutationStore(context, database).enqueue(incomeUuid)
                    database.documentDraftDao().delete(uuid)
                    CaptureSave.Saved(income = income.copy(id = id))
                } else {
                    val previousWallet = wallet?.let { candidate ->
                        requireNotNull(database.invoiceDao().getInvoiceById(candidate.id)).also { existing ->
                            require(existing.documentUuid == candidate.documentUuid && existing.origin in setOf("WALLET", "BANK") && existing.imagenUri == null && existing.financialRevision == candidate.financialRevision) { "PAYMENT_CHANGED" }
                            require(DocumentExtraction.amount(evidence.document)?.let { kotlin.math.abs(it - existing.total) < 0.005 } == true && evidence.document.currency == existing.moneda) { "PAYMENT_CHANGED" }
                        }
                    }
                    val documentUuid = previousWallet?.documentUuid ?: uuid
                    val (extractedInvoice: Invoice, products: List<Product>) = stored.toInvoice(documentUuid, promoted.toString(), previousWallet?.createdAt ?: current.createdAt)
                    val enriched = previousWallet?.let { DocumentEnrichment.invoice(it.toDomain(), extractedInvoice, keepExisting ?: true) }
                    if (enriched != null && enriched.conflicts.isNotEmpty() && keepExisting == null) return@withTransaction CaptureSave.Conflict(documentUuid, false, enriched.conflicts)
                    val invoice: Invoice = CategoryCatalog(database).assign((enriched?.value ?: extractedInvoice).copy(
                        id = previousWallet?.id ?: 0, financialRevision = (previousWallet?.financialRevision?.plus(1)) ?: 0,
                        categoria = previousWallet?.categoria ?: extractedInvoice.categoria, subcategoria = previousWallet?.subcategoria ?: extractedInvoice.subcategoria,
                        origin = previousWallet?.let { "${it.origin}_RECEIPT" } ?: "DOCUMENT", sourceName = current.sourceName, sourceMimeType = current.sourceMimeType ?: context.contentResolver.getType(promoted)), previousWallet == null)
                    val matches: List<DuplicateMatch> = DocumentDuplicates.blocking(invoice.documentIdentity(), guard.find(invoice.documentIdentity()))
                    if (matches.isNotEmpty()) return@withTransaction CaptureSave.Duplicate(matches)
                    val id: Long = if (previousWallet != null) { database.invoiceDao().updatePreservingImageState(invoice.toEntity()); previousWallet.id } else database.invoiceDao().insertInvoice(invoice.toEntity())
                    database.productDao().insertProducts(products.map { it.copy(invoiceId = id).toEntity() })
                    database.chatMessageDao().insertAndTrim(createDocumentChatMessage(context, invoice.copy(id = id).documentIdentity()))
                    FinancialMutationStore(context, database).enqueue(invoice.documentUuid)
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
                val saved: Boolean = database.invoiceDao().documentRecords().any { it.documentUuid == (wallet?.documentUuid ?: uuid) && it.imagenUri == promoted.toString() } ||
                    database.incomeDao().documentRecords().any { it.documentUuid == (bankIncome?.documentUuid ?: uuid) && it.imagenUri == promoted.toString() }
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
