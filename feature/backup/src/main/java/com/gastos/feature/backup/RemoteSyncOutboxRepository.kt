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
        deleteConsent: Boolean = false) {
        if (target.isDrive && action == RemoteSyncAction.DELETE &&
            (!deleteConsent || remoteFileId.isNullOrBlank() || accountId.isNullOrBlank())) return
        operationMutex.withLock {
            dao.upsert(RemoteSyncOutboxEntity(
                targetKey = if (target.isDrive && action == RemoteSyncAction.DELETE)
                    RemoteSyncOutboxEntity.deleteKey(target, recordId, requireNotNull(remoteFileId))
                    else RemoteSyncOutboxEntity.key(target, recordId),
                target = target, recordId = recordId, action = action, remoteFileId = remoteFileId,
                documentUuid = documentUuid, accountId = accountId, deleteConsent = deleteConsent))
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
            dataset.incomes.forEach { income ->
                add(upsert(RemoteSyncTarget.INCOME_SHEETS, income.id, income.documentUuid))
                if ((!income.imagenUri.isNullOrBlank() && (income.driveUploadPending || income.driveFileId.isNullOrBlank())) ||
                    (income.driveUploadPending && income.driveFileId != null && income.driveContentHash != null))
                    add(upsert(RemoteSyncTarget.INCOME_DRIVE, income.id, income.documentUuid))
            }
            sheetDeletes.forEach { add(RemoteSyncOutboxEntity(RemoteSyncOutboxEntity.key(it.target, it.recordId),
                it.target, it.recordId, RemoteSyncAction.DELETE)) }
        }
        operationMutex.withLock {
            val previous = dao.pending().associateBy { it.targetKey }
            val merged = wanted.map { item -> previous[item.targetKey]?.takeIf {
                it.action == item.action && it.documentUuid == item.documentUuid
            } ?: item }.toMutableList()
            if (preserveDeletes) merged += previous.values.filter {
                (it.action != RemoteSyncAction.DELETE || !it.target.isDrive || it.deleteConsent && !it.accountId.isNullOrBlank()) &&
                merged.none { replacement -> replacement.targetKey == it.targetKey } }
            dao.replaceAll(merged)
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
        pending().filter { targetKey == null || it.targetKey == targetKey }.forEach {
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
        private val AUTH_ERRORS = setOf("AUTH_REQUIRED", "WRONG_ACCOUNT", "PREMIUM_REQUIRED", "AUTH_OR_LINK_REQUIRED",
            "AUTH_RECOVERABLE", "AUTH_PERMANENT", "PLAY_SERVICES", "PERMISSION_REQUIRED")
        val RETRY_DELAYS = listOf(30_000L, 120_000L, 600_000L, 3_600_000L, 21_600_000L)
    }
}

val RemoteSyncTarget.isDrive: Boolean get() = this == RemoteSyncTarget.INVOICE_DRIVE || this == RemoteSyncTarget.INCOME_DRIVE
