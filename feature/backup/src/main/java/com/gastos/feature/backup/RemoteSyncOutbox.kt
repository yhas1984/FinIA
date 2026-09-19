package com.gastos.feature.backup

import androidx.room.Database
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import java.util.UUID

enum class RemoteSyncTarget { INVOICE_DRIVE, INCOME_DRIVE, EXPENSE_SHEETS, INCOME_SHEETS }
enum class RemoteSyncStatus { PENDING, WAITING_AUTH, FAILED }

enum class RemoteSyncAction { UPSERT, DELETE }

data class RemoteSyncDriveDelete(
    val recordId: Long,
    val remoteFileId: String
)

data class RemoteSyncSheetDelete(
    val target: RemoteSyncTarget,
    val recordId: Long
)

@Entity(tableName = "remote_sync_outbox")
data class RemoteSyncOutboxEntity(
    @PrimaryKey val targetKey: String,
    val target: RemoteSyncTarget,
    val recordId: Long,
    val action: RemoteSyncAction,
    val remoteFileId: String? = null,
    @ColumnInfo(defaultValue = "''") val operationId: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "''") val documentUuid: String = "",
    val accountId: String? = null,
    @ColumnInfo(defaultValue = "0") val deleteConsent: Boolean = false,
    @ColumnInfo(defaultValue = "'PENDING'") val status: RemoteSyncStatus = RemoteSyncStatus.PENDING,
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    @ColumnInfo(defaultValue = "0") val nextAttemptAt: Long = 0,
    val lastError: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun key(target: RemoteSyncTarget, recordId: Long) = "${target.name}:$recordId"

        fun deleteKey(target: RemoteSyncTarget, recordId: Long, remoteFileId: String) =
            "${target.name}:delete:$recordId:${remoteFileId}"
    }
}

@Dao
abstract class RemoteSyncOutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(item: RemoteSyncOutboxEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(items: List<RemoteSyncOutboxEntity>)

    @Query("SELECT * FROM remote_sync_outbox ORDER BY updatedAt ASC")
    abstract suspend fun pending(): List<RemoteSyncOutboxEntity>

    @Query("DELETE FROM remote_sync_outbox WHERE targetKey = :targetKey AND operationId = :operationId")
    abstract suspend fun deleteIfCurrent(targetKey: String, operationId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM remote_sync_outbox WHERE targetKey = :targetKey AND operationId = :operationId)")
    abstract suspend fun isCurrent(targetKey: String, operationId: String): Boolean

    @Query("SELECT * FROM remote_sync_outbox ORDER BY updatedAt ASC")
    abstract fun observe(): kotlinx.coroutines.flow.Flow<List<RemoteSyncOutboxEntity>>

    @Query("""UPDATE remote_sync_outbox SET attempts = :attempts, nextAttemptAt = :nextAt,
        status = :status, lastError = :error WHERE targetKey = :key AND operationId = :operationId""")
    abstract suspend fun updateFailure(key: String, operationId: String, attempts: Int, nextAt: Long,
        status: RemoteSyncStatus, error: String?)

    @Query("DELETE FROM remote_sync_outbox")
    abstract suspend fun clear()

    @Transaction
    open suspend fun replaceAll(items: List<RemoteSyncOutboxEntity>) {
        clear()
        if (items.isNotEmpty()) upsertAll(items)
    }
}

@Database(entities = [RemoteSyncOutboxEntity::class], version = 4, exportSchema = false)
abstract class RemoteSyncOutboxDatabase : RoomDatabase() {
    abstract fun dao(): RemoteSyncOutboxDao
}
