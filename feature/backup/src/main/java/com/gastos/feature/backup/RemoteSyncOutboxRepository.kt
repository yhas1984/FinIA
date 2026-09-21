package com.gastos.feature.backup

import com.gastos.domain.model.InvoiceType
import com.gastos.repository.BackupDataset
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteSyncOutboxRepository @Inject constructor(
    private val dao: RemoteSyncOutboxDao,
    private val queue: RemoteSyncScheduler
) {
    private val operationMutex = Mutex()
    val operations get() = dao.observe()

    suspend fun enqueue(target: RemoteSyncTarget, recordId: Long, action: RemoteSyncAction,
        remoteFileId: String? = null, documentUuid: String = "", accountId: String? = null,
        deleteConsent: Boolean = false, spreadsheetId: String? = null, status: RemoteSyncStatus = RemoteSyncStatus.PENDING) {
        if (target.isDrive && action == RemoteSyncAction.DELETE &&
            (!deleteConsent || remoteFileId.isNullOrBlank() || accountId.isNullOrBlank())) return
        operationMutex.withLock {
            dao.upsert(RemoteSyncOutboxEntity(
                targetKey = if (target.isDrive && action == RemoteSyncAction.DELETE)
                    RemoteSyncOutboxEntity.deleteKey(target, recordId, requireNotNull(remoteFileId))
                    else if (!target.isDrive && accountId != null && spreadsheetId != null && documentUuid.isNotBlank())
                        "${target.name}:$accountId:$spreadsheetId:$documentUuid"
                    else RemoteSyncOutboxEntity.key(target, recordId),
                target = target, recordId = recordId, action = action, remoteFileId = remoteFileId,
                documentUuid = documentUuid, accountId = accountId, spreadsheetId = spreadsheetId, deleteConsent = deleteConsent, status = status))
        }
        queue.schedule()
    }

    /** Restore intentionally discards every remote-image deletion, including old journals. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun reconcile(dataset: BackupDataset, sheetDeletes: List<RemoteSyncSheetDelete> = emptyList(),
        driveDeletes: List<RemoteSyncDriveDelete> = emptyList(), preserveDeletes: Boolean = false) {
        val wanted = buildList {
            dataset.invoices.filter { it.tipo == InvoiceType.GASTO }.forEach { invoice ->
                add(upsert(RemoteSyncTarget.EXPENSE_SHEETS, invoice.id, invoice.documentUuid))
                if ((!invoice.imagenUri.isNullOrBlank() && (invoice.driveUploadPending || invoice.driveFileId.isNullOrBlank())) ||
                    (invoice.driveUploadPending && invoice.driveFileId != null && invoice.driveContentHash != null))
                    add(upsert(RemoteSyncTarget.INVOICE_DRIVE, invoice.id, invoice.documentUuid))
            }
            com.gastos.domain.model.mergeIncomes(dataset.invoices, dataset.incomes).forEach { income ->
                add(upsert(RemoteSyncTarget.INCOME_SHEETS, income.id, income.documentUuid))
                if ((!income.imagenUri.isNullOrBlank() && (income.driveUploadPending || income.driveFileId.isNullOrBlank())) ||
                    (income.driveUploadPending && income.driveFileId != null && income.driveContentHash != null))
                    add(upsert(RemoteSyncTarget.INCOME_DRIVE, income.id, income.documentUuid))
            }

        }
        operationMutex.withLock {
            val previous = dao.pending().associateBy { it.targetKey }
            val merged = wanted.map { item -> (previous[item.targetKey] ?: previous.values.firstOrNull { it.target == item.target && it.documentUuid == item.documentUuid && it.action == RemoteSyncAction.UPSERT })?.takeIf {
                it.action == item.action && it.documentUuid == item.documentUuid
            } ?: item }.toMutableList()
            if (preserveDeletes) merged += previous.values.filter {
                (it.action != RemoteSyncAction.DELETE || !it.target.isDrive || it.deleteConsent && !it.accountId.isNullOrBlank()) &&
                merged.none { replacement -> replacement.targetKey == it.targetKey } }
            dao.replaceAll(merged)
        }
        queue.schedule()
    }

    suspend fun prepareDelete(target: RemoteSyncTarget, id: Long, uuid: String, account: String, book: String): RemoteSyncOutboxEntity = operationMutex.withLock {
        require(!target.isDrive && uuid.isNotBlank())
        val item = RemoteSyncOutboxEntity("${target.name}:$account:$book:$uuid", target, id, RemoteSyncAction.DELETE,
            documentUuid = uuid, accountId = account, spreadsheetId = book, status = RemoteSyncStatus.PREPARED)
        dao.upsert(item)
        queue.schedule()
        item
    }
    suspend fun confirmPrepared(item: RemoteSyncOutboxEntity) {
        dao.updateFailure(item.targetKey, item.operationId, 0, 0, RemoteSyncStatus.PENDING, null)
        queue.schedule()
    }
    suspend fun bindSheets(item: RemoteSyncOutboxEntity, account: String, book: String, uuid: String): RemoteSyncOutboxEntity? = operationMutex.withLock {
        if (!dao.isCurrent(item.targetKey, item.operationId)) return@withLock null
        dao.bind(item.targetKey, item.operationId, account, book, uuid)
        item.copy(accountId = account, spreadsheetId = book, documentUuid = uuid)
    }
    suspend fun retrySheets(account: String? = null, book: String? = null) {
        pending().filter { !it.target.isDrive && it.status != RemoteSyncStatus.PREPARED &&
            (account == null || it.belongsToSheets(account, book.orEmpty())) }.forEach {
            dao.updateFailure(it.targetKey, it.operationId, 0, 0, RemoteSyncStatus.PENDING, null)
        }
        queue.schedule()
    }

    /** One durable enqueue and one worker request for a manual sync, including historical incomes. */
    suspend fun enqueueSheetsSnapshot(dataset: BackupDataset, account: String, book: String) {
        operationMutex.withLock {
            val wanted = dataset.invoices.filter { it.tipo == InvoiceType.GASTO }.map {
                upsert(RemoteSyncTarget.EXPENSE_SHEETS, it.id, it.documentUuid)
            } + com.gastos.domain.model.mergeIncomes(dataset.invoices, dataset.incomes).map {
                upsert(RemoteSyncTarget.INCOME_SHEETS, it.id, it.documentUuid)
            }
            val previous = dao.pending()
            dao.upsertAll(wanted.map { item ->
                // Preserve the identity of an in-flight operation. Processing always reads fresh local data.
                previous.firstOrNull { it.target == item.target && it.documentUuid == item.documentUuid &&
                    it.action == RemoteSyncAction.UPSERT && it.belongsToSheets(account, book) }
                    ?: item.copy(targetKey = "${item.target.name}:$account:$book:${item.documentUuid}", accountId = account, spreadsheetId = book)
            })
        }
        queue.schedule()
    }

    private fun upsert(target: RemoteSyncTarget, id: Long, uuid: String) = RemoteSyncOutboxEntity(
        RemoteSyncOutboxEntity.key(target, id), target, id, RemoteSyncAction.UPSERT, documentUuid = uuid)

    suspend fun pending(): List<RemoteSyncOutboxEntity> = operationMutex.withLock { dao.pending() }
    suspend fun isCurrent(item: RemoteSyncOutboxEntity): Boolean = dao.isCurrent(item.targetKey, item.operationId)
    suspend fun delete(item: RemoteSyncOutboxEntity) = operationMutex.withLock {
        dao.deleteIfCurrent(item.targetKey, item.operationId)
    }

    suspend fun failed(item: RemoteSyncOutboxEntity, error: String, consumeAttempt: Boolean = true,
        permanent: Boolean = false, now: Long = System.currentTimeMillis()) {
        val attempts = item.attempts + if (consumeAttempt) 1 else 0
        val delay = if (consumeAttempt) RETRY_DELAYS.getOrNull(attempts - 1) else 30_000L
        val status = when {
            permanent || delay == null -> RemoteSyncStatus.FAILED
            !consumeAttempt && error in AUTH_ERRORS -> RemoteSyncStatus.WAITING_AUTH
            else -> RemoteSyncStatus.PENDING
        }
        dao.updateFailure(item.targetKey, item.operationId, attempts, now + (delay ?: 0), status, error)
    }

    suspend fun retry(targetKey: String? = null) {
        pending().filter { it.status != RemoteSyncStatus.PREPARED && (targetKey == null || it.targetKey == targetKey) }.forEach {
            dao.updateFailure(it.targetKey, it.operationId, 0, 0, RemoteSyncStatus.PENDING, null)
        }
        queue.schedule()
    }

    suspend fun scheduleNext() {
        pending().filter { it.status == RemoteSyncStatus.PENDING }.minOfOrNull { it.nextAttemptAt }?.let {
            queue.scheduleAfter((it - System.currentTimeMillis()).coerceAtLeast(1000))
        }
    }

    suspend fun <T> withCurrent(item: RemoteSyncOutboxEntity, block: suspend () -> T): T? =
        if (isCurrent(item)) block() else null

    companion object {
        private val AUTH_ERRORS = setOf("SHEETS_OTHER_DEVICE", "AUTH_REQUIRED", "WRONG_ACCOUNT", "PREMIUM_REQUIRED", "AUTH_OR_LINK_REQUIRED",
            "AUTH_RECOVERABLE", "AUTH_PERMANENT", "PLAY_SERVICES", "PERMISSION_REQUIRED")
        val RETRY_DELAYS = listOf(30_000L, 120_000L, 600_000L, 3_600_000L, 21_600_000L)
    }
}

val RemoteSyncTarget.isDrive: Boolean get() = this == RemoteSyncTarget.INVOICE_DRIVE || this == RemoteSyncTarget.INCOME_DRIVE

internal fun RemoteSyncOutboxEntity.belongsToSheets(account: String, book: String): Boolean =
    !target.isDrive && (accountId == null || accountId == account) && (spreadsheetId == null || spreadsheetId == book)
