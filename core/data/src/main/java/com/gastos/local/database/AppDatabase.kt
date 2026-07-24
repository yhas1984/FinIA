package com.gastos.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.gastos.data.local.entity.*
import com.gastos.local.dao.*

@Database(
    entities = [
        InvoiceEntity::class,
        ProductEntity::class,
        IncomeEntity::class,
        CountryFiscalConfigEntity::class
    ],
    version = 6,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun invoiceDao(): InvoiceDao
    abstract fun productDao(): ProductDao
    abstract fun incomeDao(): IncomeDao
    abstract fun countryFiscalConfigDao(): CountryFiscalConfigDao

    companion object {
        const val DATABASE_NAME = "gastos_ingresos_db"
    }
}
