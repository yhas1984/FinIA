package com.gastos.local.dao

import androidx.room.*
import com.gastos.data.local.entity.CommandOperationEntity

@Dao
interface CommandOperationDao {
    @Query("SELECT * FROM command_operations WHERE uuid = :uuid")
    suspend fun get(uuid: String): CommandOperationEntity?
    @Query("SELECT * FROM command_operations WHERE status = 'PENDING' ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestPending(): CommandOperationEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(operation: CommandOperationEntity)
    @Update
    suspend fun update(operation: CommandOperationEntity)
}
