package com.gastos.repository

import com.gastos.domain.model.CountryFiscalConfig
import kotlinx.coroutines.flow.Flow

interface CountryFiscalConfigRepository {
    fun getAllConfigs(): Flow<List<CountryFiscalConfig>>
    suspend fun getConfigByCountry(paisCodigo: String): CountryFiscalConfig?
    suspend fun insertConfig(config: CountryFiscalConfig)
    suspend fun updateConfig(config: CountryFiscalConfig)
    suspend fun deleteConfig(config: CountryFiscalConfig)
    suspend fun insertDefaultConfigs()
}
