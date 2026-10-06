package com.gastos.repository

import com.gastos.domain.model.Income
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.gastos.domain.model.MovementListEntry
import com.gastos.domain.model.listEntry

interface IncomeRepository {
    fun observeListEntries(): Flow<List<MovementListEntry>> = getAllIncomes().map { rows -> rows.map { it.listEntry() } }
    fun getAllIncomes(): Flow<List<Income>>
    fun getIncomesByDateRange(startDate: Long, endDate: Long): Flow<List<Income>>
    fun getIncomesByFuente(fuente: String): Flow<List<Income>>
    suspend fun getIncomeById(id: Long): Income?
    suspend fun insertIncome(income: Income): Long
    suspend fun updateIncome(income: Income)
    suspend fun updateImageSync(id: Long, documentUuid: String, sourceUri: String?, metadata: com.gastos.domain.model.DriveImageMetadata): Boolean
    suspend fun deleteIncome(income: Income)
    suspend fun getIncomeCount(): Int
    suspend fun getTotalByDateRange(startDate: Long, endDate: Long): Double?
}
