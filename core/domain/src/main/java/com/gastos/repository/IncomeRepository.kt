package com.gastos.repository

import com.gastos.domain.model.Income
import kotlinx.coroutines.flow.Flow

interface IncomeRepository {
    fun getAllIncomes(): Flow<List<Income>>
    fun getIncomesByDateRange(startDate: Long, endDate: Long): Flow<List<Income>>
    fun getIncomesByFuente(fuente: String): Flow<List<Income>>
    suspend fun getIncomeById(id: Long): Income?
    suspend fun insertIncome(income: Income): Long
    suspend fun updateIncome(income: Income)
    suspend fun deleteIncome(income: Income)
    suspend fun getIncomeCount(): Int
    suspend fun getTotalByDateRange(startDate: Long, endDate: Long): Double?
}
