package com.gastos.feature.backup

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteSyncOutboxRepository @Inject constructor(
    private val dao: RemoteSyncOutboxDao,
    private val queue: RemoteSyncScheduler
) {
    suspend fun enqueue(target: RemoteSyncTarget, recordId: Long, action: RemoteSyncAction) {
        dao.upsert(RemoteSyncOutboxEntity(RemoteSyncOutboxEntity.key(target, recordId), target, recordId, action))
        queue.schedule()
    }

    suspend fun pending(): List<RemoteSyncOutboxEntity> = dao.pending()
    suspend fun delete(targetKey: String) = dao.delete(targetKey)
}
