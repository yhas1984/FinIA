package com.gastos.local.dao

import androidx.room.*
import com.gastos.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AutomationDao {
    @Query("SELECT * FROM automation_records WHERE type IN ('BANK_ACCOUNT', 'BANK_BATCH', 'BANK_ROW')")
    fun observeBankRecords(): kotlinx.coroutines.flow.Flow<List<com.gastos.data.local.entity.AutomationRecordEntity>>
    @Query("SELECT * FROM categories ORDER BY parentId, name")
    fun observeCategories(): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM categories ORDER BY parentId, name")
    suspend fun categories(): List<CategoryEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCategory(value: CategoryEntity)
    @Update
    suspend fun updateCategory(value: CategoryEntity)
    @Query("SELECT * FROM automation_records WHERE type = :type")
    suspend fun records(type: String): List<AutomationRecordEntity>
    @Query("SELECT * FROM automation_records")
    suspend fun allRecords(): List<AutomationRecordEntity>
    @Query("SELECT * FROM automation_records WHERE id = :id")
    suspend fun record(id: String): AutomationRecordEntity?
    @Query("SELECT * FROM automation_records WHERE type = :type")
    fun observeRecords(type: String): Flow<List<AutomationRecordEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRecord(value: AutomationRecordEntity)
    @Query("DELETE FROM automation_records WHERE id = :id")
    suspend fun deleteRecord(id: String)
    @Query("SELECT * FROM monthly_limits")
    fun observeLimits(): Flow<List<MonthlyLimitEntity>>
    @Query("SELECT * FROM monthly_limits")
    suspend fun limits(): List<MonthlyLimitEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putLimit(value: MonthlyLimitEntity)
    @Query("DELETE FROM monthly_limits WHERE id = :id")
    suspend fun deleteLimit(id: String)
    @Query("DELETE FROM monthly_limits")
    suspend fun clearLimits()
    @Query("DELETE FROM automation_records")
    suspend fun clearRecords()
    @Query("DELETE FROM categories")
    suspend fun clearCategories()
}
