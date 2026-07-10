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
 * Migration 2 → 3: añade ForeignKeys con CASCADE/SET NULL e índices a la
 * tabla `products` para integridad referencial.
 *
 *   - products.invoiceId → invoices.id  ON DELETE CASCADE
 *     (borrar una factura borra sus productos)
 *   - products.categoriaId → categories.id  ON DELETE SET NULL
 *     (borrar una categoría deja los productos sin categoría)
 *
 * SQLite no permite ALTER TABLE ADD FOREIGN KEY, así que se reconstruye
 * la tabla con el patrón create-copy-drop-rename.
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
                `categoriaId` INTEGER,
                `createdAt` INTEGER NOT NULL,
                FOREIGN KEY(`invoiceId`) REFERENCES `invoices`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`categoriaId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_new_invoiceId` ON `products_new`(`invoiceId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_new_categoriaId` ON `products_new`(`categoriaId`)")
        db.execSQL("INSERT INTO `products_new` SELECT * FROM `products`")
        db.execSQL("DROP TABLE `products`")
        db.execSQL("ALTER TABLE `products_new` RENAME TO `products`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_invoiceId` ON `products`(`invoiceId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_categoriaId` ON `products`(`categoriaId`)")
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
    fun provideCategoryDao(database: AppDatabase): CategoryDao = database.categoryDao()

    @Provides
    @Singleton
    fun provideExchangeRateDao(database: AppDatabase): ExchangeRateDao = database.exchangeRateDao()

    @Provides
    @Singleton
    fun provideCountryFiscalConfigDao(database: AppDatabase): CountryFiscalConfigDao = database.countryFiscalConfigDao()
}
