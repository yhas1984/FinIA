package com.gastos.repository

import com.gastos.domain.model.ExchangeRate
import kotlinx.coroutines.flow.Flow

interface ExchangeRateRepository {
    fun getAllRates(): Flow<List<ExchangeRate>>
    suspend fun getRate(monedaOrigen: String, monedaDestino: String): ExchangeRate?
    suspend fun insertRate(rate: ExchangeRate): Long
    suspend fun updateRate(rate: ExchangeRate)
    suspend fun deleteRate(rate: ExchangeRate)
}
