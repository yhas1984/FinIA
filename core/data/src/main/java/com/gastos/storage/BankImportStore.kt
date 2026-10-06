package com.gastos.storage

import android.content.Context
import androidx.room.withTransaction
import com.gastos.data.local.entity.*
import com.gastos.domain.model.*
import com.gastos.local.database.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class BankSnapshot(val accounts: List<BankAccount>, val batches: List<BankBatch>, val transactions: List<BankTransaction>)
data class BankCreatedRecord(val row: BankTransaction, val invoice: Invoice? = null, val income: Income? = null)

@Singleton
class BankImportStore @Inject constructor(@ApplicationContext private val context: Context, private val database: AppDatabase) {
    private val dao get() = database.automationDao()
    val snapshots = dao.observeBankRecords().map { records ->
        BankSnapshot(records.filter { it.type == "BANK_ACCOUNT" }.map { AutomationCodec.json.decodeFromString(it.payload) },
            records.filter { it.type == "BANK_BATCH" }.map<AutomationRecordEntity, BankBatch> { AutomationCodec.json.decodeFromString(it.payload) }.sortedByDescending { it.createdAt },
            records.filter { it.type == "BANK_ROW" }.map { AutomationCodec.json.decodeFromString(it.payload) })
    }

    suspend fun account(name: String, currency: String): BankAccount = database.withTransaction {
        require(name.trim().length in 1..80) { "BANK_ACCOUNT" }
        require(runCatching { java.util.Currency.getInstance(currency) }.isSuccess) { "BANK_CURRENCY" }
        require(dao.records("BANK_ACCOUNT").map { AutomationCodec.json.decodeFromString<BankAccount>(it.payload) }
            .none { BankMatching.key(it.name) == BankMatching.key(name) }) { "BANK_ACCOUNT_EXISTS" }
        BankAccount("bank:account:${UUID.randomUUID()}", name.trim(), currency).also {
            dao.putRecord(AutomationRecordEntity(it.id, "BANK_ACCOUNT", AutomationCodec.json.encodeToString(it)))
        }
    }

    suspend fun stage(table: BankCsvTable, account: BankAccount, mapping: BankMapping, fileName: String, excludeInvalid: Boolean): String = database.withTransaction {
        require(dao.record(account.id)?.payload?.let { AutomationCodec.json.decodeFromString<BankAccount>(it) } == account) { "BANK_ACCOUNT" }
        val preview = BankCsv.preview(table, account, mapping)
        require(excludeInvalid || preview.none { it.error != null }) { "BANK_INVALID_ROWS" }
        val rows = preview.mapNotNull { it.transaction }
        require(rows.isNotEmpty()) { "BANK_INVALID_ROWS" }
        val id = rows.first().batchId
        dao.record(id)?.let {
            require(AutomationCodec.json.decodeFromString<BankBatch>(it.payload).mapping == mapping) { "BANK_MAPPING_CHANGED" }
            return@withTransaction id
        }
        val existing = transactions().filter { it.bankId.isNotBlank() }.associateBy { it.accountId to it.bankId }.toMutableMap()
        rows.forEach { row ->
            currentCoroutineContext().ensureActive()
            val duplicate = row.bankId.takeIf(String::isNotBlank)?.let { bankId -> existing[account.id to bankId] }
            if (duplicate != null) require(BankMatching.sameEntry(row, duplicate)) { "BANK_ID_CONFLICT" }
            val value = if (duplicate == null) row else row.copy(resolution = BankResolution.DUPLICATE, duplicateOf = duplicate.id)
            put(value)
            if (value.bankId.isNotBlank() && duplicate == null) existing[value.accountId to value.bankId] = value
        }
        val batch = BankBatch(id, account.id, table.hash, fileName.take(150), mapping, System.currentTimeMillis(), rows.size,
            preview.filter { it.error != null }.map { it.line })
        dao.putRecord(AutomationRecordEntity(id, "BANK_BATCH", AutomationCodec.json.encodeToString(batch)))
        val source = BankSource("bank:source:$id", id, table)
        dao.putRecord(AutomationRecordEntity(source.id, "BANK_SOURCE", AutomationCodec.json.encodeToString(source)))
        id
    }

    suspend fun transactions(): List<BankTransaction> = dao.records("BANK_ROW").map { AutomationCodec.json.decodeFromString(it.payload) }

    suspend fun movements(): List<BankMovement> = database.withTransaction {
        database.invoiceDao().bankIndex() +
            database.incomeDao().documentRecords().map { BankMovement(it.documentUuid, "INCOME", false,
                it.monto, it.moneda, it.fecha, it.fuente ?: it.concepto, number = DocumentEvidenceCodec.decode(it.evidenceJson)?.document?.number,
                hasDocument = it.imagenUri != null || it.driveFileId != null, revision = it.financialRevision) }
    }

    suspend fun link(id: String, expected: BankMovement) = database.withTransaction {
        val row = pending(id)
        val current = (if (expected.source == "INVOICE") database.invoiceDao().getByUuid(expected.uuid)?.let { entity ->
            BankMovement(entity.documentUuid, "INVOICE", entity.tipo == InvoiceType.GASTO, entity.total, entity.moneda, entity.fecha, entity.proveedor,
                entity.numeroFactura, entity.imagenUri != null || entity.driveFileId != null, entity.financialRevision)
        } else database.incomeDao().getByUuid(expected.uuid)?.let { entity ->
            BankMovement(entity.documentUuid, "INCOME", false, entity.monto, entity.moneda, entity.fecha, entity.fuente ?: entity.concepto,
                DocumentEvidenceCodec.decode(entity.evidenceJson)?.document?.number, entity.imagenUri != null || entity.driveFileId != null, entity.financialRevision)
        }) ?: error("BANK_MOVEMENT_CHANGED")
        require(current == expected && BankMatching.candidates(row, listOf(current)).isNotEmpty()) { "BANK_MOVEMENT_CHANGED" }
        require(transactions().none { it.id != id && it.documentUuid == current.uuid && it.source == current.source }) { "BANK_ALREADY_LINKED" }
        put(row.copy(resolution = BankResolution.LINKED, documentUuid = current.uuid, source = current.source))
    }

    suspend fun duplicate(id: String, otherId: String) = database.withTransaction {
        val row = pending(id)
        val other = requireNotNull(dao.record(otherId)).let { AutomationCodec.json.decodeFromString<BankTransaction>(it.payload) }
        require(other.id != row.id && other.batchId != row.batchId && BankMatching.sameEntry(row, other) && other.resolution != BankResolution.DUPLICATE) { "BANK_MOVEMENT_CHANGED" }
        put(row.copy(resolution = BankResolution.DUPLICATE, duplicateOf = other.id))
    }

    suspend fun classify(id: String, resolution: BankResolution) = database.withTransaction {
        require(resolution in setOf(BankResolution.TRANSFER, BankResolution.IGNORED))
        put(pending(id).copy(resolution = resolution))
    }

    suspend fun createSafeNew(batchId: String, progress: (BankProgress) -> Unit = {}): Int {
        val all = transactions()
        val original = movements()
        val existing = original.groupBy { Triple(it.expense, it.currency, java.math.BigDecimal.valueOf(it.amount).stripTrailingZeros().toPlainString()) }
        val repeated = all.groupingBy { rowKey(it) }.eachCount()
        val eligible = all.filter { it.batchId == batchId && it.resolution == BankResolution.PENDING }
        val batch: BankBatch = AutomationCodec.json.decodeFromString(dao.record(batchId)?.payload ?: error("BANK_MOVEMENT_CHANGED"))
        val claims = eligible.mapNotNull { row ->
            val candidates = existing[Triple(row.isExpense, row.currency, row.signedAmount.abs().stripTrailingZeros().toPlainString())].orEmpty()
            BankMatching.automatic(row, candidates, batch.mapping.referenceIsDocumentNumber)?.let { it.source to it.uuid }
        }.groupingBy { it }.eachCount()
        var created = 0
        var linked = 0
        val commitSize = if (eligible.size >= 100) 25 else 1
        var completed = 0
        for (chunk in eligible.chunked(commitSize)) {
            currentCoroutineContext().ensureActive()
            database.withTransaction {
                for (row in chunk) {
                    currentCoroutineContext().ensureActive()
                    val current = pending(row.id)
                    val sameAmounts = existing[Triple(row.isExpense, row.currency, row.signedAmount.abs().stripTrailingZeros().toPlainString())].orEmpty()
                    val automatic = if (batch.mapping.referenceIsDocumentNumber && current.reference.isNotBlank())
                        BankMatching.automatic(current, currentCandidates(current), true) else null
                    if (automatic != null && claims[automatic.source to automatic.uuid] == 1 && all.none { it.documentUuid == automatic.uuid && it.source == automatic.source }) {
                        try { link(row.id, automatic); linked++ }
                        catch (failure: IllegalArgumentException) { if (failure.message != "BANK_ALREADY_LINKED") throw failure }
                    } else if (!BankMatching.needsClassification(row.description) && BankMatching.candidates(row, sameAmounts).isEmpty() && repeated[rowKey(row)] == 1) {
                        if (currentCandidates(row).isEmpty()) { create(row.id, allowSeparate = true); created++ }
                    }
                }
            }
            // Report only durable commits. Cancellation rolls back the active chunk, preserving earlier ones.
            completed += chunk.size
            progress(BankProgress(completed, eligible.size, linked, created))
        }
        return created
    }

    /** Explicit creation after preview. Transaction identity makes double taps and interrupted retries safe. */
    suspend fun create(id: String, allowSeparate: Boolean = false): Unit = database.withTransaction {
        val stored = dao.record(id) ?: error("BANK_MOVEMENT_CHANGED")
        val row = AutomationCodec.json.decodeFromString<BankTransaction>(stored.payload)
        if (row.resolution == BankResolution.CREATED) return@withTransaction
        require(row.resolution == BankResolution.PENDING) { "BANK_MOVEMENT_CHANGED" }
        if (!allowSeparate) {
            require(!BankMatching.needsClassification(row.description) && BankMatching.candidates(row, movements()).isEmpty() &&
                transactions().none { it.id != id && BankMatching.sameEntry(row, it) }) { "BANK_REVIEW" }
        }
        val uuid = UUID.randomUUID().toString()
        val amount = row.signedAmount.abs().toDouble()
        val evidence = DocumentEvidence(ScannedDocument(kind = if (row.isExpense) "ticket" else "income", issuer = row.description,
            date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(row.date)), currency = row.currency, total = amount),
            fieldOrigins = listOf("issuer", "date", "currency", "total").associateWith { DocumentFieldOrigin.BANK })
        val source = if (row.isExpense) {
            val invoice = CategoryCatalog(database).assign(Invoice(documentUuid = uuid, fecha = row.date,
                proveedor = row.description, tipo = InvoiceType.GASTO, total = amount, moneda = row.currency,
                ivaPercent = null, paisCodigo = "", origin = "BANK", evidence = evidence), true)
            database.invoiceDao().insertInvoice(invoice.toEntity())
            "INVOICE"
        } else {
            val income = CategoryCatalog(database).assign(Income(documentUuid = uuid, fecha = row.date,
                concepto = row.description, fuente = row.description, monto = amount, moneda = row.currency,
                ivaPercent = null, origin = "BANK", evidence = evidence), true)
            database.incomeDao().insertIncomeEntity(income.toEntity())
            "INCOME"
        }
        FinancialMutationStore(context, database).enqueue(uuid)
        put(row.copy(resolution = BankResolution.CREATED, documentUuid = uuid, source = source, createdRevision = 0))
    }

    /** Remove only an association or exclusion; financial records have their own deletion flow. */
    suspend fun reopen(id: String) = database.withTransaction {
        val row: BankTransaction = AutomationCodec.json.decodeFromString(requireNotNull(dao.record(id)).payload)
        if (row.resolution == BankResolution.CREATED) require(movements().none { it.uuid == row.documentUuid && it.source == row.source }) { "BANK_REVIEW" }
        put(row.copy(resolution = BankResolution.PENDING, documentUuid = null, source = null, duplicateOf = null, createdRevision = null))
    }

    suspend fun source(batchId: String): BankCsvTable? = dao.record("bank:source:$batchId")?.let { AutomationCodec.json.decodeFromString<BankSource>(it.payload).table }

    suspend fun createdRecord(id: String): BankCreatedRecord = database.withTransaction {
        val row: BankTransaction = AutomationCodec.json.decodeFromString(dao.record(id)?.payload ?: error("BANK_MOVEMENT_CHANGED"))
        require(row.resolution == BankResolution.CREATED) { "BANK_REVIEW" }
        val invoice = if (row.source == "INVOICE") row.documentUuid?.let { database.invoiceDao().getByUuid(it)?.toDomain() } else null
        val income = if (row.source == "INCOME") row.documentUuid?.let { database.incomeDao().getByUuid(it)?.toDomain() } else null
        val revision = invoice?.financialRevision ?: income?.financialRevision
        val origin = invoice?.origin ?: income?.origin
        require(origin == "BANK" && revision == (row.createdRevision ?: 0L) && (invoice?.imagenUri ?: income?.imagenUri) == null &&
            (invoice?.driveFileId ?: income?.driveFileId) == null) { "BANK_REVIEW" }
        BankCreatedRecord(row, invoice, income)
    }

    suspend fun undoCreated(expected: BankCreatedRecord) = database.withTransaction {
        require(createdRecord(expected.row.id) == expected) { "BANK_MOVEMENT_CHANGED" }
        expected.invoice?.let { database.invoiceDao().deleteByIdentity(it.id, it.documentUuid) }
        expected.income?.let { database.incomeDao().deleteByIdentity(it.id, it.documentUuid) }
        expected.row.documentUuid?.let { dao.deleteRecord("sync:$it") }
        put(expected.row.copy(resolution = BankResolution.REVERSED, documentUuid = null, source = null, createdRevision = null))
    }

    suspend fun rectification(table: BankCsvTable, account: BankAccount, mapping: BankMapping, excludeInvalid: Boolean): BankRectification {
        val preview = BankCsv.preview(table, account, mapping)
        require(excludeInvalid || preview.none { it.error != null }) { "BANK_INVALID_ROWS" }
        val replacement = preview.mapNotNull { it.transaction }.associateBy { it.id }
        require(replacement.isNotEmpty()) { "BANK_INVALID_ROWS" }
        val batchId = replacement.values.first().batchId
        val existing = transactions().filter { it.batchId == batchId }
        val changes = existing.filter { row -> replacement[row.id]?.let { BankMatching.sameEntry(row, it) && row.bankId == it.bankId } != true }
        val referenced = transactions().filter { it.batchId != batchId }.mapNotNull { it.duplicateOf }.toSet()
        val reversible = changes.filter { it.resolution == BankResolution.CREATED && it.id !in referenced && runCatching { createdRecord(it.id) }.isSuccess }.map { it.id }
        val protected = changes.count { it.id in referenced || (it.resolution !in setOf(BankResolution.PENDING, BankResolution.REVERSED) && it.id !in reversible) }
        return BankRectification(changes.size + replacement.keys.count { id -> existing.none { it.id == id } }, protected, reversible)
    }

    suspend fun rectify(table: BankCsvTable, account: BankAccount, mapping: BankMapping, excludeInvalid: Boolean): String = database.withTransaction {
        val preview = BankCsv.preview(table, account, mapping)
        require(excludeInvalid || preview.none { it.error != null }) { "BANK_INVALID_ROWS" }
        val replacements = preview.mapNotNull { it.transaction }.associateBy { it.id }
        require(replacements.isNotEmpty()) { "BANK_INVALID_ROWS" }
        val id = replacements.values.first().batchId
        val batch: BankBatch = AutomationCodec.json.decodeFromString(dao.record(id)?.payload ?: error("BANK_MOVEMENT_CHANGED"))
        require(batch.accountId == account.id && batch.fileHash == table.hash) { "BANK_ACCOUNT" }
        val existing = transactions().filter { it.batchId == id }.associateBy { it.id }
        val external = transactions().filter { it.batchId != id }
        val referenced = external.mapNotNull { it.duplicateOf }.toSet()
        val replaceable = existing.values.filter { it.resolution in setOf(BankResolution.PENDING, BankResolution.REVERSED) && it.id !in referenced }.map { it.id }.toSet()
        val known = (external + existing.values.filter { it.id !in replaceable }).filter { it.bankId.isNotBlank() && it.resolution != BankResolution.DUPLICATE }
            .associateBy { it.accountId to it.bankId }.toMutableMap()
        for ((rowId, old) in existing) {
            val replacement = replacements[rowId]
            val changed = replacement == null || !BankMatching.sameEntry(old, replacement) || old.bankId != replacement.bankId
            if (changed && rowId in replaceable) dao.deleteRecord(rowId)
        }
        for ((rowId, replacement) in replacements) {
            currentCoroutineContext().ensureActive()
            val old = existing[rowId]
            val changed = old == null || !BankMatching.sameEntry(old, replacement) || old.bankId != replacement.bankId
            if (!changed || (old != null && rowId !in replaceable)) {
                old?.takeIf { it.bankId.isNotBlank() && it.resolution != BankResolution.DUPLICATE }?.let { known[it.accountId to it.bankId] = it }
                continue
            }
            val duplicate = replacement.bankId.takeIf(String::isNotBlank)?.let { known[replacement.accountId to it] }
            if (duplicate != null) require(BankMatching.sameEntry(duplicate, replacement)) { "BANK_ID_CONFLICT" }
            val value = if (duplicate == null) replacement else replacement.copy(resolution = BankResolution.DUPLICATE, duplicateOf = duplicate.id)
            put(value)
            if (duplicate == null && value.bankId.isNotBlank()) known[value.accountId to value.bankId] = value
        }
        val actual = transactions().count { it.batchId == id }
        dao.putRecord(AutomationRecordEntity(id, "BANK_BATCH", AutomationCodec.json.encodeToString(batch.copy(mapping = mapping, rows = actual, excludedLines = preview.filter { it.error != null }.map { it.line }))))
        val source = BankSource("bank:source:$id", id, table)
        dao.putRecord(AutomationRecordEntity(source.id, "BANK_SOURCE", AutomationCodec.json.encodeToString(source)))
        id
    }

    private fun rowKey(row: BankTransaction): List<String> = listOf(row.accountId, row.currency, row.amount,
        row.bookingDate.ifEmpty { row.date.toString() }, BankMatching.key(row.description), row.reference)

    private suspend fun currentCandidates(row: BankTransaction): List<BankMovement> {
        val start = row.date - 7L * 86400000
        val end = row.date + 7L * 86400000
        val amount = row.signedAmount.abs().toDouble()
        return database.invoiceDao().bankCandidates(if (row.isExpense) InvoiceType.GASTO else InvoiceType.INGRESO, row.currency, amount, start, end).map {
            BankMovement(it.documentUuid, "INVOICE", row.isExpense, it.total, it.moneda, it.fecha, it.proveedor, it.numeroFactura,
                it.imagenUri != null || it.driveFileId != null, it.financialRevision)
        } + if (row.isExpense) emptyList() else database.incomeDao().bankCandidates(row.currency, amount, start, end).map {
            BankMovement(it.documentUuid, "INCOME", false, it.monto, it.moneda, it.fecha, it.fuente ?: it.concepto,
                DocumentEvidenceCodec.decode(it.evidenceJson)?.document?.number, it.imagenUri != null || it.driveFileId != null, it.financialRevision)
        }
    }

    private suspend fun pending(id: String): BankTransaction {
        val row: BankTransaction = AutomationCodec.json.decodeFromString(dao.record(id)?.payload ?: error("BANK_MOVEMENT_CHANGED"))
        require(row.resolution == BankResolution.PENDING) { "BANK_MOVEMENT_CHANGED" }
        return row
    }
    private suspend fun put(row: BankTransaction) { dao.putRecord(AutomationRecordEntity(row.id, "BANK_ROW", AutomationCodec.json.encodeToString(row))) }
}
