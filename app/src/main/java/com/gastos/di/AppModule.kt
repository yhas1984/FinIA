package com.gastos.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gastos.local.dao.*
import com.gastos.local.database.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE incomes ADD COLUMN totalDevengado REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE incomes ADD COLUMN totalNeto REAL NOT NULL DEFAULT 0.0")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE invoices ADD COLUMN categoria TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE products ADD COLUMN categoria TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE incomes ADD COLUMN categoria TEXT DEFAULT NULL")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE invoices ADD COLUMN baseImponible REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE invoices ADD COLUMN ivaImporte REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE invoices ADD COLUMN ingresoDevengado REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE invoices ADD COLUMN ingresoDeducciones REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE invoices ADD COLUMN ingresoTipo TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE invoices ADD COLUMN conceptoIngreso TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE products ADD COLUMN comercio TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE products ADD COLUMN fechaCompra INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE incomes ADD COLUMN totalDeducciones REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE incomes ADD COLUMN tipoIngreso TEXT DEFAULT NULL")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }

    @Provides
    @Singleton
    fun provideInvoiceDao(database: AppDatabase): InvoiceDao = database.invoiceDao()

    @Provides
    @Singleton
    fun provideProductDao(database: AppDatabase): ProductDao = database.productDao()

    @Provides
    @Singleton
    fun provideIncomeDao(database: AppDatabase): IncomeDao = database.incomeDao()

    @Provides
    @Singleton
    fun provideCategoryDao(database: AppDatabase): CategoryDao = database.categoryDao()

    @Provides
    @Singleton
    fun provideExchangeRateDao(database: AppDatabase): ExchangeRateDao = database.exchangeRateDao()

    @Provides
    @Singleton
    fun provideCountryFiscalConfigDao(database: AppDatabase): CountryFiscalConfigDao = database.countryFiscalConfigDao()
}
