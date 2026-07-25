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
class Migration7To8Test {
    private lateinit var context: Context
    private val databaseName = "migration-7-8-test"

    @Before fun setUp() { context = InstrumentationRegistry.getInstrumentation().targetContext; context.deleteDatabase(databaseName) }
    @After fun tearDown() { context.deleteDatabase(databaseName) }

    @Test
    fun migrates_v7_schema_and_keeps_existing_data() {
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("""
                CREATE TABLE `invoices` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `fecha` INTEGER NOT NULL, `proveedor` TEXT NOT NULL,
                    `tipo` TEXT NOT NULL, `moneda` TEXT NOT NULL,
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
                CREATE TABLE `products` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `invoiceId` INTEGER NOT NULL, `descripcion` TEXT NOT NULL,
                    `cantidad` REAL NOT NULL, `precioUnitario` REAL NOT NULL,
                    `subtotal` REAL NOT NULL, `ivaPercent` REAL NOT NULL,
                    `ivaAmount` REAL NOT NULL, `createdAt` INTEGER NOT NULL,
                    FOREIGN KEY(`invoiceId`) REFERENCES `invoices`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX `index_products_invoiceId` ON `products` (`invoiceId`)")
            db.execSQL("""
                CREATE TABLE `incomes` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `fecha` INTEGER NOT NULL, `concepto` TEXT NOT NULL,
                    `monto` REAL NOT NULL, `totalDevengado` REAL NOT NULL,
                    `totalNeto` REAL NOT NULL, `moneda` TEXT NOT NULL,
                    `fuente` TEXT, `ivaPercent` REAL NOT NULL,
                    `irpfPercent` REAL NOT NULL, `imagenUri` TEXT, `notas` TEXT,
                    `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL
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
            db.execSQL("""
                CREATE TABLE `chat_messages` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `role` TEXT NOT NULL, `visibleText` TEXT NOT NULL,
                    `contextText` TEXT, `includeInContext` INTEGER NOT NULL DEFAULT 1,
                    `createdAt` INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("INSERT INTO `invoices` VALUES (1, 1000, 'Proveedor X', 'GASTO', 'EUR', 121.0, 21.0, 0.0, 'ES', NULL, NULL, NULL, 'drive-1', 'https://drive', 1, NULL, NULL, 10, 20)")
            db.execSQL("INSERT INTO `incomes` VALUES (1, 2000, 'Sueldo', 1000.0, 1000.0, 1000.0, 'EUR', NULL, 0.0, 0.0, NULL, NULL, 11, 22)")
            db.execSQL("INSERT INTO `country_fiscal_config` VALUES ('ES','España','21',NULL,'','IVA')")
            db.execSQL("INSERT INTO `chat_messages` VALUES (1, 'user', 'Hola', 'Hola', 1, 123)")
            db.version = 7
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_7_8)
            .build()
        db.openHelper.writableDatabase.use { sqlite ->
            sqlite.query("SELECT proveedor, categoria FROM invoices WHERE id=1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Proveedor X", c.getString(0))
                assertEquals(null, c.getString(1))
            }
            sqlite.query("SELECT concepto, categoria FROM incomes WHERE id=1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Sueldo", c.getString(0))
                assertEquals(null, c.getString(1))
            }
            sqlite.execSQL("UPDATE invoices SET categoria='Alimentación' WHERE id=1")
            sqlite.execSQL("UPDATE incomes SET categoria='Nómina' WHERE id=1")
            sqlite.query("SELECT categoria FROM invoices WHERE id=1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Alimentación", c.getString(0))
            }
        }
        db.close()
    }
}
