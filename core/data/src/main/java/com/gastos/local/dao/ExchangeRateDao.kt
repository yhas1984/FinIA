package com.gastos.local.dao

import androidx.room.*
import com.gastos.data.local.entity.ExchangeRateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExchangeRateDao {

    @Query("SELECT * FROM exchange_rates ORDER BY fecha DESC")
    fun getAllRates(): Flow<List<ExchangeRateEntity>>

    @Query("""
        SELECT * FROM exchange_rates 
        WHERE monedaOrigen = :monedaOrigen AND monedaDestino = :monedaDestino 
        ORDER BY fecha DESC LIMIT 1
    """)
    suspend fun getRate(monedaOrigen: String, monedaDestino: String): ExchangeRateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRate(rate: ExchangeRateEntity): Long

    @Update
    suspend fun updateRate(rate: ExchangeRateEntity)

    @Delete
    suspend fun deleteRate(rate: ExchangeRateEntity)
}
