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
class Migration6To7Test {
    private lateinit var context: Context
    private val databaseName = "migration-6-7-test"

    @Before fun setUp() { context = InstrumentationRegistry.getInstrumentation().targetContext; context.deleteDatabase(databaseName) }
    @After fun tearDown() { context.deleteDatabase(databaseName) }

    @Test
    fun migrates_v6_schema_and_keeps_financial_data() {
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
            db.execSQL(
                """
                INSERT INTO `invoices` (
                    id, fecha, proveedor, tipo, moneda, total, ivaPercent,
                    irpfPercent, paisCodigo, nifEmisor, nifReceptor, imagenUri,
                    driveFileId, driveWebViewLink, driveUploadPending, ocrRawText,
                    notas, createdAt, updatedAt
                ) VALUES (
                    1, 1000, 'Proveedor X', 'GASTO', 'EUR', 121.0, 21.0,
                    0.0, 'ES', NULL, NULL, NULL, 'drive-1',
                    'https://drive', 1, NULL, NULL, 10, 20
                )
                """.trimIndent()
            )
            db.execSQL("INSERT INTO `incomes` VALUES (1, 2000, 'Sueldo', 1000.0, 1000.0, 1000.0, 'EUR', NULL, 0.0, 0.0, NULL, NULL, 11, 22)")
            db.execSQL("INSERT INTO `country_fiscal_config` VALUES ('ES','España','21',NULL,'','IVA')")
            db.version = 6
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).addMigrations(MIGRATION_6_7).build()
        db.openHelper.writableDatabase.use { sqlite ->
            sqlite.query("SELECT COUNT(*) FROM chat_messages").use { c -> assertTrue(c.moveToFirst()) }
            sqlite.execSQL("INSERT INTO chat_messages (role, visibleText, contextText, includeInContext, createdAt) VALUES ('user','Hola','Hola',1,123)")
            sqlite.query("SELECT COUNT(*) FROM chat_messages").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
            sqlite.query("SELECT proveedor, total FROM invoices WHERE id=1").use { c -> assertTrue(c.moveToFirst()); assertEquals("Proveedor X", c.getString(0)); assertEquals(121.0, c.getDouble(1), 0.0) }
        }
        db.close()
    }
}
