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

/**
 * Migration 2 → 3: reconstruye la tabla `products` para que coincida con
 * el esquema v3 exportado:
 *
 *   - Se ELIMINA la columna `categoriaId` (las categorías se retiraron del
 *     modelo en v3) y la FK que apuntaba a `categories`.
 *   - Se AÑADE products.invoiceId → invoices.id  ON DELETE CASCADE
 *     (borrar una factura borra sus productos).
 *   - Se eliminan las tablas retiradas `categories` y `exchange_rates`.
 *
 * SQLite no permite ALTER TABLE ADD/DROP FOREIGN KEY ni DROP COLUMN hasta
 * versiones recientes, así que se usa el patrón create-copy-drop-rename.
 * El DDL es exactamente el del esquema v3 exportado (schema JSON), de lo
 * contrario la validación de Room fallaría al abrir la base de datos.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `products_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `invoiceId` INTEGER NOT NULL,
                `descripcion` TEXT NOT NULL,
                `cantidad` REAL NOT NULL,
                `precioUnitario` REAL NOT NULL,
                `subtotal` REAL NOT NULL,
                `ivaPercent` REAL NOT NULL,
                `ivaAmount` REAL NOT NULL,
                `createdAt` INTEGER NOT NULL,
                FOREIGN KEY(`invoiceId`) REFERENCES `invoices`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        // Copia explícita de columnas: la v2 tenía `categoriaId`, que en v3
        // ya no existe; un SELECT * rompería el INSERT.
        db.execSQL(
            """
            INSERT INTO `products_new` (`id`, `invoiceId`, `descripcion`, `cantidad`, `precioUnitario`, `subtotal`, `ivaPercent`, `ivaAmount`, `createdAt`)
            SELECT `id`, `invoiceId`, `descripcion`, `cantidad`, `precioUnitario`, `subtotal`, `ivaPercent`, `ivaAmount`, `createdAt` FROM `products`
            """.trimIndent()
        )
        db.execSQL("DROP TABLE `products`")
        db.execSQL("ALTER TABLE `products_new` RENAME TO `products`")
        // El nombre del índice debe ser el que Room espera (index_products_invoiceId).
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_invoiceId` ON `products`(`invoiceId`)")

        // Tablas retiradas del modelo en v3. Room no valida tablas extra,
        // pero las eliminamos para no arrastrar esquema muerto.
        db.execSQL("DROP TABLE IF EXISTS `categories`")
        db.execSQL("DROP TABLE IF EXISTS `exchange_rates`")
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
    fun provideCountryFiscalConfigDao(database: AppDatabase): CountryFiscalConfigDao = database.countryFiscalConfigDao()
}
