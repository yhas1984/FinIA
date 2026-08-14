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

    suspend fun enqueue(
        target: RemoteSyncTarget,
        recordId: Long,
        action: RemoteSyncAction,
        remoteFileId: String? = null
    ) {
        operationMutex.withLock {
            dao.upsert(
                RemoteSyncOutboxEntity(
                    targetKey = RemoteSyncOutboxEntity.key(target, recordId),
                    target = target,
                    recordId = recordId,
                    action = action,
                    remoteFileId = remoteFileId
                )
            )
        }
        queue.schedule()
    }

    suspend fun reconcile(
        dataset: BackupDataset,
        sheetDeletes: List<RemoteSyncSheetDelete> = emptyList(),
        driveDeletes: List<RemoteSyncDriveDelete> = emptyList()
    ) {
        val items = buildList {
            dataset.invoices
                .filter { it.tipo == InvoiceType.GASTO }
                .forEach { invoice ->
                    add(
                        RemoteSyncOutboxEntity(
                            targetKey = RemoteSyncOutboxEntity.key(RemoteSyncTarget.EXPENSE_SHEETS, invoice.id),
                            target = RemoteSyncTarget.EXPENSE_SHEETS,
                            recordId = invoice.id,
                            action = RemoteSyncAction.UPSERT
                        )
                    )
                    if ((invoice.driveUploadPending || !invoice.driveFileId.isNullOrBlank()) && !invoice.imagenUri.isNullOrBlank()) {
                        add(
                            RemoteSyncOutboxEntity(
                                targetKey = RemoteSyncOutboxEntity.key(RemoteSyncTarget.INVOICE_DRIVE, invoice.id),
                                target = RemoteSyncTarget.INVOICE_DRIVE,
                                recordId = invoice.id,
                                action = RemoteSyncAction.UPSERT
                            )
                        )
                    }
                }
            dataset.incomes.forEach { income ->
                add(
                    RemoteSyncOutboxEntity(
                        targetKey = RemoteSyncOutboxEntity.key(RemoteSyncTarget.INCOME_SHEETS, income.id),
                        target = RemoteSyncTarget.INCOME_SHEETS,
                        recordId = income.id,
                        action = RemoteSyncAction.UPSERT
                    )
                )
            }
            sheetDeletes.forEach { deletion ->
                add(
                    RemoteSyncOutboxEntity(
                        targetKey = RemoteSyncOutboxEntity.key(deletion.target, deletion.recordId),
                        target = deletion.target,
                        recordId = deletion.recordId,
                        action = RemoteSyncAction.DELETE
                    )
                )
            }
            driveDeletes.forEach { deletion ->
                add(
                    RemoteSyncOutboxEntity(
                        targetKey = RemoteSyncOutboxEntity.deleteKey(
                            RemoteSyncTarget.INVOICE_DRIVE,
                            deletion.recordId,
                            deletion.remoteFileId
                        ),
                        target = RemoteSyncTarget.INVOICE_DRIVE,
                        recordId = deletion.recordId,
                        action = RemoteSyncAction.DELETE,
                        remoteFileId = deletion.remoteFileId
                    )
                )
            }
        }
        operationMutex.withLock { dao.replaceAll(items) }
        queue.schedule()
    }

    suspend fun pending(): List<RemoteSyncOutboxEntity> = operationMutex.withLock { dao.pending() }

    suspend fun delete(item: RemoteSyncOutboxEntity) {
        operationMutex.withLock { dao.deleteIfCurrent(item.targetKey, item.operationId) }
    }

    suspend fun <T> withCurrent(item: RemoteSyncOutboxEntity, block: suspend () -> T): T? =
        operationMutex.withLock { dao.isCurrent(item.targetKey, item.operationId) }
            .takeIf { it }
            ?.let { block() }
}
