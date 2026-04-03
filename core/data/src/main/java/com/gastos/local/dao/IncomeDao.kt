package com.gastos.local.dao

import androidx.room.*
import com.gastos.data.local.entity.IncomeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IncomeDao {

    @Query("SELECT * FROM incomes ORDER BY fecha DESC")
    fun getAllIncomes(): Flow<List<IncomeEntity>>

    @Query("SELECT * FROM incomes WHERE fecha BETWEEN :startDate AND :endDate ORDER BY fecha DESC")
    fun getIncomesByDateRange(startDate: Long, endDate: Long): Flow<List<IncomeEntity>>

    @Query("SELECT * FROM incomes WHERE fuente LIKE '%' || :fuente || '%' ORDER BY fecha DESC")
    fun getIncomesByFuente(fuente: String): Flow<List<IncomeEntity>>

    @Query("SELECT * FROM incomes WHERE id = :id")
    suspend fun getIncomeById(id: Long): IncomeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIncomeEntity(income: IncomeEntity): Long

    @Update
    suspend fun updateIncomeEntity(income: IncomeEntity)

    @Delete
    suspend fun deleteIncomeEntity(income: IncomeEntity)

    @Query("SELECT COUNT(*) FROM incomes")
    suspend fun getIncomeCount(): Int

    @Query("SELECT SUM(monto) FROM incomes WHERE fecha BETWEEN :startDate AND :endDate")
    suspend fun getTotalByDateRange(startDate: Long, endDate: Long): Double?
}
