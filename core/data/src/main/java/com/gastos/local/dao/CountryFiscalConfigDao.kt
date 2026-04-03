package com.gastos.local.dao

import androidx.room.*
import com.gastos.data.local.entity.CountryFiscalConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CountryFiscalConfigDao {

    @Query("SELECT * FROM country_fiscal_config ORDER BY nombrePais ASC")
    fun getAllConfigs(): Flow<List<CountryFiscalConfigEntity>>

    @Query("SELECT * FROM country_fiscal_config WHERE paisCodigo = :paisCodigo")
    suspend fun getConfigByCountry(paisCodigo: String): CountryFiscalConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfig(config: CountryFiscalConfigEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfigs(configs: List<CountryFiscalConfigEntity>)

    @Update
    suspend fun updateConfig(config: CountryFiscalConfigEntity)

    @Delete
    suspend fun deleteConfig(config: CountryFiscalConfigEntity)

    @Query("SELECT COUNT(*) FROM country_fiscal_config")
    suspend fun getConfigCount(): Int
}
