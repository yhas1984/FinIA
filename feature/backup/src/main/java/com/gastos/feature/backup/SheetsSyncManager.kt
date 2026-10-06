@file:Suppress("DEPRECATION")
package com.gastos.feature.backup

import android.content.Context
import com.gastos.domain.model.*
import com.gastos.repository.*
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** One-way synchronization. Every queued mutation belongs to one document, account and workbook. */
@Singleton
class SheetsSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val premiumStatus: PremiumStatusProvider,
    private val sheetsExportService: SheetsExportService,
    private val invoiceRepository: InvoiceRepository,
    private val incomeRepository: IncomeRepository,
    private val productRepository: ProductRepository,
    private val exchangeRateProvider: ExchangeRateProvider,
    private val currencyPreference: CurrencyPreference,
    private val sheetsLinkStore: SheetsLinkStore,
    private val operationCoordinator: SheetsOperationCoordinator,
    private val remoteSyncOutboxRepository: RemoteSyncOutboxRepository,
    private val remoteSyncScheduler: RemoteSyncScheduler,
    private val snapshots: BackupDataRepository
) {
    val operations get() = remoteSyncOutboxRepository.operations
    fun isRecoveryPaused(): Boolean {
        val account = sheetsExportService.getLastSignedInAccount() ?: return false
        return sheetsLinkStore.isRecoveryPaused(accountKey(account), getStoredId(account))
    }
    fun isEnabled(account: GoogleSignInAccount): Boolean = getStoredId(account).isNotBlank()
    fun getStoredId(account: GoogleSignInAccount): String = sheetsLinkStore.getSpreadsheetId(account)
    fun setSpreadsheetId(account: GoogleSignInAccount, id: String) {
        sheetsLinkStore.setSpreadsheetId(account, id)
        remoteSyncScheduler.schedule()
    }
    private fun accountKey(account: GoogleSignInAccount): String = SheetsLinkStore.getAccountPreferenceKey(account.id, account.email)
    private suspend fun enqueue(target: RemoteSyncTarget, id: Long, uuid: String) {
        val account: GoogleSignInAccount? = sheetsExportService.getLastSignedInAccount()
        remoteSyncOutboxRepository.enqueue(target, id, RemoteSyncAction.UPSERT, documentUuid = uuid,
            accountId = account?.let(::accountKey), spreadsheetId = account?.let(::getStoredId)?.takeIf(String::isNotBlank))
    }
    suspend fun upsertExpense(invoice: Invoice) {
        if (invoice.tipo != InvoiceType.GASTO) return
        enqueue(RemoteSyncTarget.EXPENSE_SHEETS, invoice.id, invoice.documentUuid)
        if (!invoice.imagenUri.isNullOrBlank() && (invoice.driveUploadPending || invoice.driveFileId.isNullOrBlank()))
            remoteSyncOutboxRepository.enqueue(RemoteSyncTarget.INVOICE_DRIVE, invoice.id, RemoteSyncAction.UPSERT, documentUuid = invoice.documentUuid)
    }
    suspend fun upsertIncome(income: Income) {
        enqueue(RemoteSyncTarget.INCOME_SHEETS, income.id, income.documentUuid)
        if (!income.imagenUri.isNullOrBlank() && (income.driveUploadPending || income.driveFileId.isNullOrBlank()))
            remoteSyncOutboxRepository.enqueue(RemoteSyncTarget.INCOME_DRIVE, income.id, RemoteSyncAction.UPSERT, documentUuid = income.documentUuid)
    }
    @Suppress("UNUSED_PARAMETER")
    suspend fun syncExpense(invoice: Invoice, products: List<Product>) = upsertExpense(invoice)

    suspend fun prepareExpenseDelete(invoice: Invoice): RemoteSyncOutboxEntity? = prepareDelete(RemoteSyncTarget.EXPENSE_SHEETS, invoice.id, invoice.documentUuid)
    suspend fun prepareIncomeDelete(income: Income): RemoteSyncOutboxEntity? = prepareDelete(RemoteSyncTarget.INCOME_SHEETS, income.id, income.documentUuid)
    private suspend fun prepareDelete(target: RemoteSyncTarget, id: Long, uuid: String): RemoteSyncOutboxEntity? {
        val account: GoogleSignInAccount? = sheetsExportService.getLastSignedInAccount()
        val destination: Pair<String, String> = if (account != null) {
            val book: String = getStoredId(account).takeIf(String::isNotBlank) ?: return null
            accountKey(account) to book
        } else sheetsLinkStore.lastDestination() ?: return null
        return remoteSyncOutboxRepository.prepareDelete(target, id, uuid, destination.first, destination.second)
    }
    suspend fun deleteLocal(invoice: Invoice, beforeDelete: suspend () -> Unit = {}) = operationCoordinator.localDeletionMutex.withLock {
        val item = prepareExpenseDelete(invoice)
        beforeDelete()
        invoiceRepository.deleteInvoice(invoice)
        confirmDelete(item)
    }
    suspend fun deleteLocal(income: Income, beforeDelete: suspend () -> Unit = {}) = operationCoordinator.localDeletionMutex.withLock {
        val item = prepareIncomeDelete(income)
        beforeDelete()
        incomeRepository.deleteIncome(income)
        confirmDelete(item)
    }
    internal suspend fun confirmRemoteImageDeletion(item: RemoteSyncOutboxEntity): Boolean = operationCoordinator.localDeletionMutex.withLock {
        val currentUuid: String? = if (item.target == RemoteSyncTarget.INVOICE_DRIVE)
            invoiceRepository.getInvoiceById(item.recordId)?.documentUuid else incomeRepository.getIncomeById(item.recordId)?.documentUuid
        if (currentUuid == item.documentUuid) { remoteSyncOutboxRepository.delete(item); false }
        else { remoteSyncOutboxRepository.confirmPrepared(item); true }
    }
    suspend fun confirmDelete(item: RemoteSyncOutboxEntity?) {
        try { item?.let { remoteSyncOutboxRepository.confirmPrepared(it) } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {
            // PREPARED is already durable. The worker will check the missing local UUID after restart.
            com.gastos.extension.SafeLog.w("SheetsSyncManager", "Deletion confirmation pending recovery")
        }
    }
    fun currentOperations(operations: List<RemoteSyncOutboxEntity>): List<RemoteSyncOutboxEntity> {
        val account: GoogleSignInAccount = sheetsExportService.getLastSignedInAccount() ?: return emptyList()
        val book: String = getStoredId(account).takeIf(String::isNotBlank) ?: return emptyList()
        return operations.filter { it.belongsToSheets(accountKey(account), book) }
    }

    /** The button waits for Google's acknowledgement, not just for a background job to be queued. */
    suspend fun syncChanges(): Int = operationCoordinator.syncMutex.withLock {
        check(premiumStatus.isPremium.value) { "PREMIUM_REQUIRED" }
        check(sheetsExportService.isSignedIn()) { "AUTH_REQUIRED" }
        val account: GoogleSignInAccount = requireNotNull(sheetsExportService.getLastSignedInAccount())
        val book: String = getStoredId(account).takeIf(String::isNotBlank) ?: error("AUTH_OR_LINK_REQUIRED")
        val key: String = accountKey(account)
        sheetsLinkStore.setRecoveryPaused(key, book, false)
        // Capture operation identities BEFORE the data, so a later edit can never be acknowledged by this batch.
        val (items, data) = operationCoordinator.localDeletionMutex.withLock {
            val snapshot = snapshots.financialSnapshot()
            val merged = mergeIncomes(snapshot.invoices, snapshot.incomes)
            val productsByInvoice = snapshot.products.groupBy { it.invoiceId }
            val dirty = snapshot.invoices.filter { it.tipo == InvoiceType.GASTO }.filter {
                sheetsLinkStore.fingerprint(key, book, it.sheetKey().fingerprintKey) != fingerprint(it, null, productsByInvoice[it.id].orEmpty())
            }.map { it.sheetKey() }.toSet() + merged.filter {
                sheetsLinkStore.fingerprint(key, book, it.sheetKey().fingerprintKey) != fingerprint(null, it, if (it.id < 0) productsByInvoice[-it.id].orEmpty() else emptyList())
            }.map { it.sheetKey() }
            val dirtyInvoices = snapshot.invoices.filter { it.sheetKey() in dirty }
            val parentIds = dirtyInvoices.map { it.id }.toSet()
            remoteSyncOutboxRepository.enqueueSheetsSnapshot(snapshot.copy(invoices = dirtyInvoices,
                incomes = snapshot.incomes.filter { it.sheetKey() in dirty }, products = snapshot.products.filter { it.invoiceId in parentIds }), key, book)
            remoteSyncOutboxRepository.retrySheets(key, book)
            remoteSyncOutboxRepository.pending().filter { it.belongsToSheets(key, book) } to snapshots.financialSnapshot()
        }
        syncSelected(account, book, key, items, data, includeSnapshot = true)
        remoteSyncOutboxRepository.pending().count { it.belongsToSheets(key, book) }
    }

    /** Eligible background items share one snapshot and bounded atomic document batches. */
    internal suspend fun processBatch(candidates: List<RemoteSyncOutboxEntity>): Set<String> = operationCoordinator.syncMutex.withLock {
        if (!premiumStatus.isPremium.value || !sheetsExportService.isSignedIn()) return@withLock emptySet()
        val account = sheetsExportService.getLastSignedInAccount() ?: return@withLock emptySet()
        val book = getStoredId(account).takeIf(String::isNotBlank) ?: return@withLock emptySet()
        val key = accountKey(account)
        if (sheetsLinkStore.isRecoveryPaused(key, book)) return@withLock emptySet()
        val eligible = candidates.filter { !it.target.isDrive && it.status != RemoteSyncStatus.PREPARED && it.belongsToSheets(key, book) }
        if (eligible.isEmpty()) return@withLock emptySet()
        val data = snapshots.financialSnapshot()
        val items = eligible.mapNotNull { remoteSyncOutboxRepository.bindSheets(it, key, book, it.documentUuid) }
        syncSelected(account, book, key, items, data, consumeFailures = true)
        items.mapTo(mutableSetOf()) { it.operationId }
    }

    private suspend fun syncSelected(account: GoogleSignInAccount, book: String, key: String,
        items: List<RemoteSyncOutboxEntity>, data: BackupDataset, consumeFailures: Boolean = false, includeSnapshot: Boolean = false) {
        val incomes = mergeIncomes(data.invoices, data.incomes)
        val productsByInvoice = data.products.groupBy { it.invoiceId }
        val expenseByUuid = data.invoices.filter { it.tipo == InvoiceType.GASTO }.associateBy { it.sheetKey() }
        val incomeByUuid = incomes.associateBy { it.sheetKey() }
        val acknowledgements = expenseByUuid.mapValues { (_, invoice) -> fingerprint(invoice, null, productsByInvoice[invoice.id].orEmpty()) } +
            incomeByUuid.mapValues { (_, income) -> fingerprint(null, income, if (income.id < 0) productsByInvoice[-income.id].orEmpty() else emptyList()) }
        val upserts = items.filter { it.action == RemoteSyncAction.UPSERT && it.sheetKey() in acknowledgements }
        val dirty = (if (includeSnapshot) acknowledgements.keys.toList() else upserts.mapNotNull { it.sheetKey() }).distinct().filter { sheetsLinkStore.fingerprint(key, book, it.fingerprintKey) != acknowledgements[it] }
        val deletions = items.filter { it.action == RemoteSyncAction.DELETE && it.documentUuid.isNotBlank() &&
            it.accountId == key && it.spreadsheetId == book && when (it.target) {
                RemoteSyncTarget.EXPENSE_SHEETS -> it.sheetKey() !in expenseByUuid
                RemoteSyncTarget.INCOME_SHEETS -> it.sheetKey() !in incomeByUuid
                else -> false
            } }.mapNotNull { it.sheetKey() }.toSet()
        try {
            val version = sheetsExportService.schemaVersion(account, book)
            check(version == null || version <= SheetsSchema.SCHEMA_VERSION) { "SHEETS_NEWER_SCHEMA" }
            if (version != SheetsSchema.SCHEMA_VERSION) sheetsExportService.exportToSheets(account, data.invoices, data.incomes, data.products, book)
            (dirty + deletions).distinct().chunked(SHEETS_DOCUMENT_BATCH_SIZE).forEach { batch ->
                val changed = batch.toSet()
                val expenses = data.invoices.filter { it.sheetKey() in changed }
                val nativeIncomes = data.incomes.filter { it.sheetKey() in changed }
                val parentIds = expenses.map { it.id }.toSet()
                sheetsExportService.syncDocuments(account, book, expenses, nativeIncomes,
                    data.products.filter { it.invoiceId in parentIds }, deletions.intersect(changed))
                sheetsLinkStore.recordFingerprints(key, book, batch.associate { it.fingerprintKey to acknowledgements[it] })
                items.filter { it.sheetKey() in changed }.forEach { remoteSyncOutboxRepository.delete(it) }
            }
            // Old queued upserts may be identical to their confirmed remote version.
            items.filter { it.sheetKey() !in dirty && it.sheetKey() !in deletions }.forEach { remoteSyncOutboxRepository.delete(it) }
            sheetsExportService.ensureAppearance(account, book)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            val code = sheetsFailureCode(error)
            val classified = GoogleApiErrorClassifier.classify(error,
                GoogleApiErrorContext("sync", "OFFLINE", "SERVER_UNAVAILABLE", "PERMISSION_OR_QUOTA", "SYNC_FAILED"))
            val waiting = code == "SHEETS_OTHER_DEVICE" || classified.category in setOf(GoogleApiErrorCategory.NETWORK,
                GoogleApiErrorCategory.AUTH_RECOVERABLE, GoogleApiErrorCategory.AUTH_PERMANENT, GoogleApiErrorCategory.PLAY_SERVICES)
            items.forEach { remoteSyncOutboxRepository.failed(it, code, consumeAttempt = consumeFailures && !waiting,
                permanent = consumeFailures && !waiting && (code.startsWith("SHEETS_") || !classified.shouldRetry)) }
            throw error
        }
    }

    private companion object { const val SHEETS_DOCUMENT_BATCH_SIZE = 50 }

    internal suspend fun process(original: RemoteSyncOutboxEntity): Boolean = operationCoordinator.syncMutex.withLock {
        processUnlocked(original)
    }

    private suspend fun processUnlocked(original: RemoteSyncOutboxEntity): Boolean {
        if (!premiumStatus.isPremium.value || !sheetsExportService.isSignedIn()) return false
        val account: GoogleSignInAccount = sheetsExportService.getLastSignedInAccount() ?: return false
        val book: String = getStoredId(account).takeIf(String::isNotBlank) ?: return false
        if (original.accountId != null && original.accountId != accountKey(account)) return false
        if (original.spreadsheetId != null && original.spreadsheetId != book) return false
        check(!sheetsLinkStore.isRecoveryPaused(accountKey(account), book)) { "SHEETS_RECOVERY_PAUSED" }
        val snapshot: BackupDataset = snapshots.documentSnapshot(original.target == RemoteSyncTarget.INCOME_SHEETS, original.recordId)
        val invoice: Invoice? = snapshot.invoices.firstOrNull { it.tipo == InvoiceType.GASTO }
        val income: Income? = mergeIncomes(snapshot.invoices, snapshot.incomes).firstOrNull()
        val uuid: String? = invoice?.documentUuid ?: income?.documentUuid
        if (original.status == RemoteSyncStatus.PREPARED) {
            val survives: Boolean = operationCoordinator.localDeletionMutex.withLock {
                val currentUuid = if (original.target == RemoteSyncTarget.EXPENSE_SHEETS)
                    invoiceRepository.getInvoiceById(original.recordId)?.documentUuid else incomeRepository.getIncomeById(original.recordId)?.documentUuid
                if (currentUuid != original.documentUuid) remoteSyncOutboxRepository.confirmPrepared(original)
                currentUuid == original.documentUuid
            }
            if (survives) { remoteSyncOutboxRepository.delete(original); return true }
        }
        if (original.action == RemoteSyncAction.DELETE && (original.documentUuid.isBlank() || original.accountId == null || original.spreadsheetId == null)) return true
        if (original.action == RemoteSyncAction.UPSERT && (uuid == null || original.documentUuid.isNotBlank() && uuid != original.documentUuid)) return true
        if (original.status != RemoteSyncStatus.PREPARED && original.action == RemoteSyncAction.DELETE && uuid == original.documentUuid) return true
        val item: RemoteSyncOutboxEntity = remoteSyncOutboxRepository.bindSheets(original, accountKey(account), book, original.documentUuid.ifBlank { uuid.orEmpty() }) ?: return true
        val products: List<Product> = snapshot.products
        val fingerprint: String? = if (item.action == RemoteSyncAction.DELETE) null else fingerprint(invoice, income, products)
        if (fingerprint != null && sheetsLinkStore.fingerprint(accountKey(account), book, requireNotNull(item.sheetKey()).fingerprintKey) == fingerprint) {
            sheetsExportService.ensureAppearance(account, book)
            return true
        }
        operationCoordinator.migrationMutex.withLock {
            val version: Int? = sheetsExportService.schemaVersion(account, book)
            check(version == null || version <= SheetsSchema.SCHEMA_VERSION) { "SHEETS_NEWER_SCHEMA" }
            if (version != SheetsSchema.SCHEMA_VERSION) {
                val all: BackupDataset = snapshots.financialSnapshot()
                sheetsExportService.exportToSheets(account, all.invoices, all.incomes, all.products, book)
            }
        }
        if (!remoteSyncOutboxRepository.isCurrent(item)) return true
        sheetsExportService.syncDocument(account, book, if (item.action == RemoteSyncAction.DELETE) emptyList() else listOfNotNull(invoice),
            if (item.action == RemoteSyncAction.DELETE) emptyList() else listOfNotNull(income), if (item.action == RemoteSyncAction.DELETE) emptyList() else products,
            deletion = item.sheetKey().takeIf { item.action == RemoteSyncAction.DELETE })
        sheetsLinkStore.recordFingerprint(accountKey(account), book, requireNotNull(item.sheetKey()).fingerprintKey, fingerprint)
        return true
    }

    private fun fingerprint(invoice: Invoice?, income: Income?, products: List<Product>): String {
        val text = invoice.toString() + income.toString() + products.sortedBy { it.id }.toString() +
            currencyPreference.defaultCurrency.value + exchangeRateProvider.rates.value.toSortedMap().toString() +
            exchangeRateProvider.lastUpdated.value + SheetsSchema.SCHEMA_VERSION + ":" + SheetsSchema.RENDER_REVISION
        return java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

private fun Invoice.sheetKey() = SheetsDocumentKey(if (tipo == InvoiceType.GASTO) RemoteSyncTarget.EXPENSE_SHEETS else RemoteSyncTarget.INCOME_SHEETS, documentUuid)
private fun Income.sheetKey() = SheetsDocumentKey(RemoteSyncTarget.INCOME_SHEETS, documentUuid)
private fun RemoteSyncOutboxEntity.sheetKey(): SheetsDocumentKey? = if (target.isDrive || documentUuid.isBlank()) null else SheetsDocumentKey(target, documentUuid)
