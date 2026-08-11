package com.gastos.feature.backup

import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

enum class RemoteSyncTarget { INVOICE_DRIVE, EXPENSE_SHEETS, INCOME_SHEETS }
enum class RemoteSyncAction { UPSERT, DELETE }

@Entity(tableName = "remote_sync_outbox")
data class RemoteSyncOutboxEntity(
    @PrimaryKey val targetKey: String,
    val target: RemoteSyncTarget,
    val recordId: Long,
    val action: RemoteSyncAction,
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun key(target: RemoteSyncTarget, recordId: Long) = "${target.name}:$recordId"
    }
}

@Dao
interface RemoteSyncOutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: RemoteSyncOutboxEntity)

    @Query("SELECT * FROM remote_sync_outbox ORDER BY updatedAt ASC")
    suspend fun pending(): List<RemoteSyncOutboxEntity>

    @Query("DELETE FROM remote_sync_outbox WHERE targetKey = :targetKey")
    suspend fun delete(targetKey: String)

    @Query("DELETE FROM remote_sync_outbox")
    suspend fun clear()
}

@Database(entities = [RemoteSyncOutboxEntity::class], version = 1, exportSchema = false)
abstract class RemoteSyncOutboxDatabase : RoomDatabase() {
    abstract fun dao(): RemoteSyncOutboxDao
}
