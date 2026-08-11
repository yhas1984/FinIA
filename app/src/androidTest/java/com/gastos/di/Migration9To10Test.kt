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
class Migration9To10Test {
    private lateinit var context: Context
    private val databaseName = "migration-9-10-test"

    @Before fun setUp() { context = InstrumentationRegistry.getInstrumentation().targetContext; context.deleteDatabase(databaseName) }
    @After fun tearDown() { context.deleteDatabase(databaseName) }

    @Test
    fun migrates_v9_schema_and_keeps_existing_data() {
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("""
                CREATE TABLE `invoices` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `fecha` INTEGER NOT NULL, `proveedor` TEXT NOT NULL,
                    `tipo` TEXT NOT NULL, `categoria` TEXT,
                    `subcategoria` TEXT, `moneda` TEXT NOT NULL,
                    `total` REAL NOT NULL, `ivaPercent` REAL NOT NULL,
                    `irpfPercent` REAL NOT NULL, `paisCodigo` TEXT NOT NULL,
                    `nifEmisor` TEXT, `nifReceptor` TEXT, `imagenUri` TEXT,
                    `driveFileId` TEXT, `driveWebViewLink` TEXT,
                    `driveUploadPending` INTEGER NOT NULL DEFAULT 0,
                    `ocrRawText` TEXT, `notas` TEXT,
                    `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE `incomes` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `fecha` INTEGER NOT NULL, `concepto` TEXT NOT NULL,
                    `monto` REAL NOT NULL, `totalDevengado` REAL NOT NULL,
                    `totalNeto` REAL NOT NULL, `moneda` TEXT NOT NULL,
                    `fuente` TEXT, `categoria` TEXT, `subcategoria` TEXT,
                    `ivaPercent` REAL NOT NULL, `irpfPercent` REAL NOT NULL,
                    `imagenUri` TEXT, `notas` TEXT,
                    `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE `products` (
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
            """.trimIndent())
            db.execSQL("CREATE INDEX `index_products_invoiceId` ON `products` (`invoiceId`)")
            db.execSQL("""
                CREATE TABLE `country_fiscal_config` (
                    `paisCodigo` TEXT NOT NULL,
                    `nombrePais` TEXT NOT NULL,
                    `ivaRates` TEXT NOT NULL,
                    `irpfRate` REAL,
                    `nifFormat` TEXT NOT NULL,
                    `nombreLeyFiscal` TEXT NOT NULL,
                    PRIMARY KEY(`paisCodigo`)
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE `chat_messages` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `role` TEXT NOT NULL,
                    `visibleText` TEXT NOT NULL,
                    `contextText` TEXT,
                    `includeInContext` INTEGER NOT NULL DEFAULT 1,
                    `createdAt` INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("INSERT INTO `invoices` VALUES (1, 1000, 'Proveedor X', 'GASTO', 'Alimentación', 'Supermercado', 'EUR', 121.0, 21.0, 0.0, 'ES', NULL, NULL, NULL, 'drive-1', 'https://drive', 1, NULL, NULL, 10, 20)")
            db.execSQL("INSERT INTO `incomes` VALUES (1, 2000, 'Sueldo', 1000.0, 1000.0, 1000.0, 'EUR', NULL, 'Nómina', 'Mensual', 0.0, 0.0, NULL, NULL, 11, 22)")
            db.version = 9
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_9_10)
            .build()
        db.openHelper.writableDatabase.use { sqlite ->
            sqlite.query("PRAGMA table_info(invoices)").use { c ->
                var foundNumero = false
                var foundBase = false
                var foundCuota = false
                while (c.moveToNext()) {
                    when (c.getString(1)) {
                        "numeroFactura" -> foundNumero = true
                        "baseImponible" -> foundBase = true
                        "cuotaIva" -> foundCuota = true
                    }
                }
                assertTrue(foundNumero && foundBase && foundCuota)
            }
            sqlite.query("SELECT proveedor, subcategoria, numeroFactura, baseImponible, cuotaIva FROM invoices WHERE id=1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Proveedor X", c.getString(0))
                assertEquals("Supermercado", c.getString(1))
                assertEquals(null, c.getString(2))
                assertTrue(c.isNull(3))
                assertTrue(c.isNull(4))
            }
            sqlite.query("SELECT concepto, subcategoria FROM incomes WHERE id=1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Sueldo", c.getString(0))
                assertEquals("Mensual", c.getString(1))
            }
        }
        db.close()
    }
}
