package com.gastos.di

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.local.database.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration4To5Test {
    private lateinit var context: Context
    private val databaseName = "migration-4-5-test"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migratesLegacyIncomeAndPreservesProductsInNotes() {
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE `invoices` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `fecha` INTEGER NOT NULL, `proveedor` TEXT NOT NULL,
                    `tipo` TEXT NOT NULL, `moneda` TEXT NOT NULL,
                    `total` REAL NOT NULL, `ivaPercent` REAL NOT NULL,
                    `irpfPercent` REAL NOT NULL, `paisCodigo` TEXT NOT NULL,
                    `nifEmisor` TEXT, `nifReceptor` TEXT, `imagenUri` TEXT,
                    `ocrRawText` TEXT, `notas` TEXT, `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE `products` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `invoiceId` INTEGER NOT NULL, `descripcion` TEXT NOT NULL,
                    `cantidad` REAL NOT NULL, `precioUnitario` REAL NOT NULL,
                    `subtotal` REAL NOT NULL, `ivaPercent` REAL NOT NULL,
                    `ivaAmount` REAL NOT NULL, `createdAt` INTEGER NOT NULL,
                    FOREIGN KEY(`invoiceId`) REFERENCES `invoices`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX `index_products_invoiceId` ON `products` (`invoiceId`)")
            db.execSQL(
                """
                CREATE TABLE `incomes` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `fecha` INTEGER NOT NULL, `concepto` TEXT NOT NULL,
                    `monto` REAL NOT NULL, `totalDevengado` REAL NOT NULL,
                    `totalNeto` REAL NOT NULL, `moneda` TEXT NOT NULL,
                    `fuente` TEXT, `ivaPercent` REAL NOT NULL,
                    `irpfPercent` REAL NOT NULL, `imagenUri` TEXT, `notas` TEXT,
                    `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE `country_fiscal_config` (
                    `paisCodigo` TEXT NOT NULL, `nombrePais` TEXT NOT NULL,
                    `ivaRates` TEXT NOT NULL, `irpfRate` REAL,
                    `nifFormat` TEXT NOT NULL, `nombreLeyFiscal` TEXT NOT NULL,
                    PRIMARY KEY(`paisCodigo`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO `invoices` VALUES (
                    7, 1000, 'Cliente ACME', 'INGRESO', 'USD', 250.0,
                    21.0, 15.0, 'ES', 'B123', NULL, 'content://invoice/7',
                    NULL, 'Nota original', 900, 950
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO `products` VALUES (
                    3, 7, 'Consultoría', 2.0, 100.0, 200.0, 21.0, 42.0, 900
                )
                """.trimIndent()
            )
            db.version = 4
        }

        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
            .build()
        val sqlite = database.openHelper.writableDatabase

        sqlite.query("SELECT concepto, monto, moneda, notas FROM incomes").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Cliente ACME", cursor.getString(0))
            assertEquals(250.0, cursor.getDouble(1), 0.0)
            assertEquals("USD", cursor.getString(2))
            assertTrue(cursor.getString(3).contains("Consultoría"))
        }
        sqlite.query("SELECT COUNT(*) FROM invoices").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        sqlite.query("SELECT COUNT(*) FROM products").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        database.close()
    }
}
