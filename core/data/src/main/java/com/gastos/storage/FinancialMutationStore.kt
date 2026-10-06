package com.gastos.storage

import android.content.Context
import androidx.room.withTransaction
import com.gastos.data.local.entity.*
import com.gastos.domain.model.*
import com.gastos.local.database.AppDatabase
import com.gastos.repository.impl.DocumentGuard
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FinancialMutationStore @Inject constructor(@ApplicationContext private val context: Context, private val database: AppDatabase) {
    suspend fun latestUndoId(): String? {
        val values = database.automationDao().records("MUTATION").map { AutomationCodec.json.decodeFromString<FinancialMutation>(it.payload) }
        val invoices = database.invoiceDao().documentRecords().associate { it.documentUuid to it.financialRevision }
        val incomes = database.incomeDao().documentRecords().associate { it.documentUuid to it.financialRevision }
        return values.filter { !it.undone && System.currentTimeMillis() - it.createdAt <= UNDO_RETENTION && (invoices[it.documentUuid] ?: incomes[it.documentUuid]) == it.expectedRevision }.maxByOrNull { it.createdAt }?.id
    }
    suspend fun candidates(kind: DocumentKind, uuid: String?, description: String?, last: Boolean): List<DocumentIdentity> {
        val invoices: List<Invoice> = database.invoiceDao().documentRecords().map(InvoiceEntity::toDomain)
        val incomes: List<Income> = database.incomeDao().documentRecords().map(IncomeEntity::toDomain)
        val rows: List<DocumentIdentity> = if (kind == DocumentKind.EXPENSE) invoices.filter { it.tipo == InvoiceType.GASTO }.map { it.documentIdentity() }
            else mergeIncomes(invoices, incomes).map { it.documentIdentity() }
        if (uuid != null) return rows.filter { it.uuid == uuid }
        if (!description.isNullOrBlank()) {
            val matching = invoices.filter { record -> listOfNotNull(record.proveedor, record.categoria, record.subcategoria, record.numeroFactura).any { AutomationCodec.key(it).contains(AutomationCodec.key(description)) } }.map { it.documentUuid }.toSet() +
                incomes.filter { record -> listOfNotNull(record.concepto, record.fuente, record.categoria, record.subcategoria).any { AutomationCodec.key(it).contains(AutomationCodec.key(description)) } }.map { it.documentUuid }
            return rows.filter { it.uuid in matching }
        }
        if (!last) return emptyList()
        val newest: String? = if (kind == DocumentKind.EXPENSE) invoices.filter { it.tipo == InvoiceType.GASTO }.maxWithOrNull(compareBy<Invoice> { it.createdAt }.thenBy { it.id })?.documentUuid
            else mergeIncomes(invoices, incomes).maxWithOrNull(compareBy<Income> { it.createdAt }.thenBy { it.documentUuid })?.documentUuid
        return rows.filter { it.uuid == newest }
    }
    suspend fun update(operationId: String, uuid: String, patch: MovementPatch, expectedRevision: Long? = null): FinancialMutation = database.withTransaction {
        database.automationDao().record("mutation:$operationId")?.let { return@withTransaction AutomationCodec.json.decodeFromString(it.payload) }
        require(patch.description?.isNotBlank() != false && patch.merchant?.isNotBlank() != false) { "PATCH_INVALID" }
        require(patch.amount?.let { it.isFinite() && it > 0 } != false) { "AMOUNT_INVALID" }
        require(patch.currency == null || runCatching { java.util.Currency.getInstance(patch.currency) }.isSuccess) { "CURRENCY_INVALID" }
        val before: Invoice? = database.invoiceDao().documentRecords().firstOrNull { it.documentUuid == uuid }?.toDomain()
        val income: Income? = database.incomeDao().documentRecords().firstOrNull { it.documentUuid == uuid }?.toDomain()
        require(before != null || income != null) { "MOVEMENT_MISSING" }
        val revision: Long = before?.financialRevision ?: requireNotNull(income).financialRevision
        require(expectedRevision == null || expectedRevision == revision) { "MOVEMENT_CHANGED" }
        val now: Long = System.currentTimeMillis()
        val catalog: CategoryCatalog = CategoryCatalog(database)
        if (before != null) {
            require(patch.currency == null || patch.currency == before.moneda || before.taxes.isEmpty() && before.baseImponible == null && before.cuotaIva == null && before.ivaPercent == null && database.productDao().getProductsByInvoiceId(before.id).first().isEmpty()) { "CURRENCY_BREAKDOWN" }
            val assigned: Invoice = catalog.assign(before.copy(total = patch.amount ?: before.total, fecha = patch.date ?: before.fecha,
                proveedor = patch.merchant ?: patch.description ?: before.proveedor, categoria = patch.category ?: before.categoria,
                subcategoria = if (patch.category != null && patch.category != before.categoria) patch.subcategory else patch.subcategory ?: before.subcategoria,
                notas = patch.notes ?: before.notas, moneda = patch.currency ?: before.moneda,
                manualAmountAdjusted = before.manualAmountAdjusted || patch.amount != null && patch.amount != before.total,
                financialRevision = revision + 1, updatedAt = now))
            val source = before.evidence?.document ?: ScannedDocument(kind = if (before.tipo == InvoiceType.GASTO) "ticket" else "income", issuer = before.proveedor, total = before.total, currency = before.moneda,
                date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(before.fecha)))
            val evidence = DocumentProvenance.markManual(before.evidence ?: DocumentEvidence(source), source.copy(total = assigned.total, issuer = assigned.proveedor,
                date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(assigned.fecha)), currency = assigned.moneda,
                category = assigned.categoria, subcategory = assigned.subcategoria))
            val changed = assigned.copy(evidence = evidence)
            DocumentGuard(database).check(changed.documentIdentity())
            database.invoiceDao().updatePreservingImageState(changed.toEntity())
        } else {
            val original: Income = requireNotNull(income)
            require(patch.currency == null || patch.currency == original.moneda || original.taxes.isEmpty() && original.totalDevengado == 0.0 && original.totalNeto == 0.0) { "CURRENCY_BREAKDOWN" }
            val assigned: Income = catalog.assign(original.copy(monto = patch.amount ?: original.monto, fecha = patch.date ?: original.fecha,
                concepto = patch.description ?: original.concepto, fuente = patch.merchant ?: original.fuente,
                categoria = patch.category ?: original.categoria,
                subcategoria = if (patch.category != null && patch.category != original.categoria) patch.subcategory else patch.subcategory ?: original.subcategoria,
                notas = patch.notes ?: original.notas, moneda = patch.currency ?: original.moneda,
                manualAmountAdjusted = original.manualAmountAdjusted || patch.amount != null && patch.amount != original.monto,
                financialRevision = revision + 1, updatedAt = now))
            val source = original.evidence?.document ?: ScannedDocument(kind = "income", issuer = original.fuente ?: original.concepto, total = original.monto, currency = original.moneda,
                date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(original.fecha)))
            val evidence = DocumentProvenance.markManual(original.evidence ?: DocumentEvidence(source), source.copy(total = assigned.monto, issuer = assigned.fuente ?: assigned.concepto,
                date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(assigned.fecha)), currency = assigned.moneda,
                category = assigned.categoria, subcategory = assigned.subcategoria))
            val changed = assigned.copy(evidence = if (assigned.concepto != original.concepto) evidence.copy(fieldOrigins = evidence.fieldOrigins + ("concept" to DocumentFieldOrigin.MANUAL)) else evidence)
            DocumentGuard(database).check(changed.documentIdentity())
            database.incomeDao().updatePreservingImageState(changed.toEntity())
        }
        val kind: DocumentKind = if (income != null || before?.tipo == InvoiceType.INGRESO) DocumentKind.INCOME else DocumentKind.EXPENSE
        val text: String = context.getString(com.gastos.data.R.string.movement_updated) + if (patch.amount != null) "\n" + context.getString(com.gastos.data.R.string.amount_manually_adjusted) else ""
        val mutation: FinancialMutation = FinancialMutation(operationId, uuid, kind, before, income, revision + 1, now, resultText = text)
        database.automationDao().putRecord(AutomationRecordEntity("mutation:$operationId", "MUTATION", AutomationCodec.json.encodeToString(mutation)))
        enqueue(uuid)
        recordReceipt(operationId, uuid, kind, text)
        mutation
    }
    suspend fun undo(operationId: String, mutationId: String? = null): String = database.withTransaction {
        database.automationDao().record("undo:$operationId")?.let { return@withTransaction it.payload }
        val mutation: FinancialMutation = database.automationDao().records("MUTATION").map { AutomationCodec.json.decodeFromString<FinancialMutation>(it.payload) }
            .filter { !it.undone && (mutationId == null || it.id == mutationId) }.maxByOrNull { it.createdAt } ?: error("UNDO_MISSING")
        require(System.currentTimeMillis() - mutation.createdAt <= UNDO_RETENTION) { "UNDO_EXPIRED" }
        val invoice: InvoiceEntity? = database.invoiceDao().documentRecords().firstOrNull { it.documentUuid == mutation.documentUuid }
        val income: IncomeEntity? = database.incomeDao().documentRecords().firstOrNull { it.documentUuid == mutation.documentUuid }
        require((invoice?.financialRevision ?: income?.financialRevision) == mutation.expectedRevision) { "MOVEMENT_CHANGED" }
        mutation.beforeInvoice?.let { before ->
            val restored = before.copy(imagenUri = invoice?.imagenUri, financialRevision = mutation.expectedRevision + 1, updatedAt = System.currentTimeMillis())
            DocumentGuard(database).check(restored.documentIdentity())
            database.invoiceDao().updatePreservingImageState(restored.toEntity())
        }
        mutation.beforeIncome?.let { before ->
            val restored = before.copy(imagenUri = income?.imagenUri, financialRevision = mutation.expectedRevision + 1, updatedAt = System.currentTimeMillis())
            DocumentGuard(database).check(restored.documentIdentity())
            database.incomeDao().updatePreservingImageState(restored.toEntity())
        }
        database.automationDao().putRecord(AutomationRecordEntity("mutation:${mutation.id}", "MUTATION", AutomationCodec.json.encodeToString(mutation.copy(undone = true))))
        val text: String = context.getString(com.gastos.data.R.string.movement_undo_success)
        database.automationDao().putRecord(AutomationRecordEntity("undo:$operationId", "UNDO", text))
        enqueue(mutation.documentUuid)
        recordReceipt(operationId, mutation.documentUuid, mutation.kind, text)
        text
    }
    suspend fun enqueue(uuid: String) {
        val preferences: android.content.SharedPreferences = context.getSharedPreferences("finai_sheets_sync", Context.MODE_PRIVATE)
        val intent: SyncIntent = SyncIntent("sync:$uuid", uuid, preferences.getString("last_account_key", null), preferences.getString("last_workbook", null))
        database.automationDao().putRecord(AutomationRecordEntity(intent.id, "SYNC", AutomationCodec.json.encodeToString(intent)))
    }
    private suspend fun recordReceipt(operationId: String, uuid: String, kind: DocumentKind, text: String) {
        database.chatMessageDao().insertAndTrim(ChatMessageEntity(role = "model", visibleText = text, includeInContext = false, operationUuid = operationId,
            documentUuid = uuid, documentKind = kind.name))
        database.commandOperationDao().get(operationId)?.let { database.commandOperationDao().update(it.copy(status = "SAVED", resultKind = "UPDATE", resultUuid = uuid, resultText = text)) }
    }
    companion object { const val UNDO_RETENTION: Long = 30L * 24 * 60 * 60 * 1000 }
}
