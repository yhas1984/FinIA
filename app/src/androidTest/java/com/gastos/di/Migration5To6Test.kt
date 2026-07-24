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
class Migration5To6Test {
    private lateinit var context: Context
    private val databaseName = "migration-5-6-test"

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
    fun addsDriveMetadataWithoutQueuingExistingInvoices() {
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
                    5, 1000, 'Proveedor', 'GASTO', 'EUR', 121.0,
                    21.0, 0.0, 'ES', 'B123', NULL, 'content://invoice/5',
                    NULL, NULL, 900, 950
                )
                """.trimIndent()
            )
            db.version = 5
        }

        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_5_6)
            .build()
        val sqlite = database.openHelper.writableDatabase

        sqlite.query(
            "SELECT driveFileId, driveWebViewLink, driveUploadPending, imagenUri FROM invoices WHERE id=5"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
            assertEquals(0, cursor.getInt(2))
            assertEquals("content://invoice/5", cursor.getString(3))
        }
        database.close()
    }
}
