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

enum class RemoteSyncTarget { INVOICE_DRIVE, EXPENSE_SHEETS, INCOME_SHEETS }
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
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun key(target: RemoteSyncTarget, recordId: Long) = "${target.name}:$recordId"

        fun deleteKey(target: RemoteSyncTarget, recordId: Long, remoteFileId: String) =
            "${target.name}:delete:$recordId:${remoteFileId.hashCode()}"
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

    @Query("DELETE FROM remote_sync_outbox")
    abstract suspend fun clear()

    @Transaction
    open suspend fun replaceAll(items: List<RemoteSyncOutboxEntity>) {
        clear()
        if (items.isNotEmpty()) upsertAll(items)
    }
}

@Database(entities = [RemoteSyncOutboxEntity::class], version = 3, exportSchema = false)
abstract class RemoteSyncOutboxDatabase : RoomDatabase() {
    abstract fun dao(): RemoteSyncOutboxDao
}
