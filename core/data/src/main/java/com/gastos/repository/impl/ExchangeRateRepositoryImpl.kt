package com.gastos.repository.impl

import com.gastos.data.local.entity.toDomain
import com.gastos.data.local.entity.toEntity
import com.gastos.local.dao.ExchangeRateDao
import com.gastos.domain.model.ExchangeRate
import com.gastos.repository.ExchangeRateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExchangeRateRepositoryImpl @Inject constructor(
    private val exchangeRateDao: ExchangeRateDao
) : ExchangeRateRepository {

    override fun getAllRates(): Flow<List<ExchangeRate>> =
        exchangeRateDao.getAllRates().map { list -> list.map { it.toDomain() } }

    override suspend fun getRate(monedaOrigen: String, monedaDestino: String): ExchangeRate? =
        exchangeRateDao.getRate(monedaOrigen, monedaDestino)?.toDomain()

    override suspend fun insertRate(rate: ExchangeRate): Long =
        exchangeRateDao.insertRate(rate.toEntity())

    override suspend fun updateRate(rate: ExchangeRate) =
        exchangeRateDao.updateRate(rate.toEntity())

    override suspend fun deleteRate(rate: ExchangeRate) =
        exchangeRateDao.deleteRate(rate.toEntity())
}