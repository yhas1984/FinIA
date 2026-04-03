package com.gastos.repository.impl

import com.gastos.data.local.entity.toDomain
import com.gastos.data.local.entity.toEntity
import com.gastos.local.dao.IncomeDao
import com.gastos.domain.model.Income
import com.gastos.repository.IncomeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IncomeRepositoryImpl @Inject constructor(
    private val incomeDao: IncomeDao
) : IncomeRepository {

    override fun getAllIncomes(): Flow<List<Income>> =
        incomeDao.getAllIncomes().map { list -> list.map { it.toDomain() } }

    override fun getIncomesByDateRange(startDate: Long, endDate: Long): Flow<List<Income>> =
        incomeDao.getIncomesByDateRange(startDate, endDate).map { list -> list.map { it.toDomain() } }

    override fun getIncomesByFuente(fuente: String): Flow<List<Income>> =
        incomeDao.getIncomesByFuente(fuente).map { list -> list.map { it.toDomain() } }

    override suspend fun getIncomeById(id: Long): Income? =
        incomeDao.getIncomeById(id)?.toDomain()

    override suspend fun insertIncome(income: Income): Long =
        incomeDao.insertIncomeEntity(income.toEntity().copy(updatedAt = System.currentTimeMillis()))

    override suspend fun updateIncome(income: Income) =
        incomeDao.updateIncomeEntity(income.toEntity().copy(updatedAt = System.currentTimeMillis()))

    override suspend fun deleteIncome(income: Income) =
        incomeDao.deleteIncomeEntity(income.toEntity())

    override suspend fun getIncomeCount(): Int = incomeDao.getIncomeCount()

    override suspend fun getTotalByDateRange(startDate: Long, endDate: Long): Double? =
        incomeDao.getTotalByDateRange(startDate, endDate)
}