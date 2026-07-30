package com.gastos.repository.impl

import com.gastos.data.local.entity.toDomain
import com.gastos.data.local.entity.toEntity
import com.gastos.local.dao.CountryFiscalConfigDao
import com.gastos.domain.model.CountryFiscalConfig
import com.gastos.repository.CountryFiscalConfigRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CountryFiscalConfigRepositoryImpl @Inject constructor(
    private val countryFiscalConfigDao: CountryFiscalConfigDao
) : CountryFiscalConfigRepository {

    override fun getAllConfigs(): Flow<List<CountryFiscalConfig>> =
        countryFiscalConfigDao.getAllConfigs().map { list -> list.map { it.toDomain() } }

    override suspend fun getConfigByCountry(paisCodigo: String): CountryFiscalConfig? =
        countryFiscalConfigDao.getConfigByCountry(paisCodigo)?.toDomain()

    override suspend fun insertConfig(config: CountryFiscalConfig) =
        countryFiscalConfigDao.insertConfig(config.toEntity())

    override suspend fun updateConfig(config: CountryFiscalConfig) =
        countryFiscalConfigDao.updateConfig(config.toEntity())

    override suspend fun deleteConfig(config: CountryFiscalConfig) =
        countryFiscalConfigDao.deleteConfig(config.toEntity())

    override suspend fun insertDefaultConfigs() {
        val defaultConfigs = createDefaultConfigs().map { config: CountryFiscalConfig -> config.toEntity() }
        countryFiscalConfigDao.insertConfigsIfMissing(defaultConfigs)
    }

    private fun createDefaultConfigs(): List<CountryFiscalConfig> = listOf(
        CountryFiscalConfig(
            paisCodigo = "ES",
            nombrePais = "España",
            ivaRates = listOf(4.0, 10.0, 21.0),
            irpfRate = 15.0,
            nifFormat = "X-XXXXXXXX-X",
            nombreLeyFiscal = "IVA"
        ),
        CountryFiscalConfig(
            paisCodigo = "MX",
            nombrePais = "México",
            ivaRates = listOf(0.0, 8.0, 16.0),
            irpfRate = null,
            nifFormat = "XXXX-XX-XXXX",
            nombreLeyFiscal = "IVA"
        ),
        CountryFiscalConfig(
            paisCodigo = "US",
            nombrePais = "Estados Unidos",
            ivaRates = listOf(0.0),
            irpfRate = null,
            nifFormat = "XXX-XX-XXXX",
            nombreLeyFiscal = "Sales Tax"
        ),
        CountryFiscalConfig(
            paisCodigo = "AR",
            nombrePais = "Argentina",
            ivaRates = listOf(0.0, 10.5, 21.0, 27.0),
            irpfRate = null,
            nifFormat = "XX-XXXXXXXX-X",
            nombreLeyFiscal = "IVA"
        ),
        CountryFiscalConfig(
            paisCodigo = "CO",
            nombrePais = "Colombia",
            ivaRates = listOf(0.0, 5.0, 19.0),
            irpfRate = null,
            nifFormat = "XXXXXXXXX-X",
            nombreLeyFiscal = "IVA"
        ),
        CountryFiscalConfig(
            paisCodigo = "CL",
            nombrePais = "Chile",
            ivaRates = listOf(19.0),
            irpfRate = null,
            nifFormat = "XX.XXX.XXX-X",
            nombreLeyFiscal = "IVA"
        ),
        CountryFiscalConfig(
            paisCodigo = "PE",
            nombrePais = "Perú",
            ivaRates = listOf(0.0, 18.0),
            irpfRate = null,
            nifFormat = "XXXXXXXXXX",
            nombreLeyFiscal = "IGV"
        ),
        CountryFiscalConfig(
            paisCodigo = "EC",
            nombrePais = "Ecuador",
            ivaRates = listOf(0.0, 5.0, 15.0),
            irpfRate = null,
            nifFormat = "XXXXXXXXXXXXX",
            nombreLeyFiscal = "IVA"
        ),
        CountryFiscalConfig(
            paisCodigo = "BO",
            nombrePais = "Bolivia",
            ivaRates = listOf(0.0, 13.0),
            irpfRate = null,
            nifFormat = "XXXXXXXXXXXX",
            nombreLeyFiscal = "IVA"
        ),
        CountryFiscalConfig(
            paisCodigo = "GT",
            nombrePais = "Guatemala",
            ivaRates = listOf(0.0, 12.0),
            irpfRate = null,
            nifFormat = "XXXXXXXX-X",
            nombreLeyFiscal = "IVA"
        ),
        CountryFiscalConfig(
            paisCodigo = "NI",
            nombrePais = "Nicaragua",
            ivaRates = listOf(0.0, 15.0),
            irpfRate = null,
            nifFormat = "XXXXXXXXXXXXX",
            nombreLeyFiscal = "IVA"
        ),
        CountryFiscalConfig(
            paisCodigo = "PY",
            nombrePais = "Paraguay",
            ivaRates = listOf(0.0, 5.0, 10.0),
            irpfRate = null,
            nifFormat = "XXXXXXXX-X",
            nombreLeyFiscal = "IVA"
        ),
        CountryFiscalConfig(
            paisCodigo = "UY",
            nombrePais = "Uruguay",
            ivaRates = listOf(0.0, 10.0, 22.0),
            irpfRate = null,
            nifFormat = "XXXXXXXXXXXX",
            nombreLeyFiscal = "IVA"
        ),
        CountryFiscalConfig(
            paisCodigo = "VE",
            nombrePais = "Venezuela",
            ivaRates = listOf(0.0, 8.0, 16.0, 31.0),
            irpfRate = null,
            nifFormat = "X-XXXXXXXX-X",
            nombreLeyFiscal = "IVA"
        )
    )
}
