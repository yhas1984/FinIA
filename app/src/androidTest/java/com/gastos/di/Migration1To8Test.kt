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
class Migration1To8Test {
    private lateinit var context: Context
    private val databaseName = "migration-1-8-test"

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
    fun migratesFullChainToVersion8AndKeepsDataAccessible() {
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("""
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
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE `products` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `invoiceId` INTEGER NOT NULL, `descripcion` TEXT NOT NULL,
                    `cantidad` REAL NOT NULL, `precioUnitario` REAL NOT NULL,
                    `subtotal` REAL NOT NULL, `ivaPercent` REAL NOT NULL,
                    `ivaAmount` REAL NOT NULL, `categoriaId` INTEGER, `createdAt` INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE `incomes` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `fecha` INTEGER NOT NULL, `concepto` TEXT NOT NULL,
                    `monto` REAL NOT NULL, `moneda` TEXT NOT NULL,
                    `fuente` TEXT, `ivaPercent` REAL NOT NULL,
                    `irpfPercent` REAL NOT NULL, `imagenUri` TEXT, `notas` TEXT,
                    `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE `categories` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `nombre` TEXT NOT NULL, `icono` TEXT NOT NULL,
                    `color` INTEGER NOT NULL, `esDefault` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE `exchange_rates` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `monedaOrigen` TEXT NOT NULL, `monedaDestino` TEXT NOT NULL,
                    `tasa` REAL NOT NULL, `fecha` INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE `country_fiscal_config` (
                    `paisCodigo` TEXT NOT NULL, `nombrePais` TEXT NOT NULL,
                    `ivaRates` TEXT NOT NULL, `irpfRate` REAL,
                    `nifFormat` TEXT NOT NULL, `nombreLeyFiscal` TEXT NOT NULL,
                    PRIMARY KEY(`paisCodigo`)
                )
            """.trimIndent())
            db.execSQL("INSERT INTO `invoices` VALUES (7, 1000, 'Cliente ACME', 'INGRESO', 'USD', 250.0, 21.0, 15.0, 'ES', 'B123', NULL, NULL, NULL, 'Nota original', 900, 950)")
            db.execSQL("INSERT INTO `products` VALUES (3, 7, 'Consultoría', 2.0, 100.0, 200.0, 21.0, 42.0, NULL, 900)")
            db.execSQL("INSERT INTO `country_fiscal_config` VALUES ('ES','España','21',NULL,'','IVA')")
            db.version = 1
        }

        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10
            )
            .build()
        database.openHelper.writableDatabase.use { sqlite ->
            sqlite.query("SELECT concepto, monto, categoria FROM incomes").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Cliente ACME", cursor.getString(0))
                assertEquals(250.0, cursor.getDouble(1), 0.0)
                assertEquals(null, cursor.getString(2))
            }
            sqlite.query("SELECT COUNT(*) FROM chat_messages").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
        database.close()
    }
}
