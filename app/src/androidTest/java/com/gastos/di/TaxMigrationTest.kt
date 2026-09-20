package com.gastos.di

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.data.local.entity.*
import com.gastos.domain.model.*
import com.gastos.local.database.AppDatabase
import com.gastos.repository.impl.BackupDataRepositoryImpl
import com.gastos.repository.impl.InvoiceRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaxMigrationTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun migrationPreservesEveryOldColumnProductRelationAndSequence() = runBlocking {
        val name = "tax-migration-13-14"
        context.deleteDatabase(name)
        val before = mutableMapOf<String, List<String?>>()
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            val schema = InstrumentationRegistry.getInstrumentation().context.assets
                .open("com.gastos.local.database.AppDatabase/13.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                for (i in 0 until (indices?.length() ?: 0)) db.execSQL(indices!!.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            db.execSQL("INSERT INTO invoices (documentUuid,id,fecha,proveedor,tipo,moneda,total,numeroFactura,baseImponible,cuotaIva,ivaPercent,irpfPercent,paisCodigo,imagenUri,driveFileId,driveAccountId,createdAt,updatedAt) VALUES ('expense',7,1,'Synthetic','GASTO','EUR',110,'SYNTHETIC',100,10,10,0,'ES','local','remote','test-account',1,1)")
            db.execSQL("INSERT INTO incomes (documentUuid,id,fecha,concepto,monto,totalDevengado,totalNeto,moneda,ivaPercent,irpfPercent,createdAt,updatedAt) VALUES ('income',8,1,'Synthetic',104,104,104,'EUR',4,0,1,1)")
            db.execSQL("INSERT INTO products (id,invoiceId,descripcion,cantidad,precioUnitario,subtotal,ivaPercent,ivaAmount,pricesIncludeTax,createdAt) VALUES (9,7,'Zero rate',1,10,10,0,0,0,1)")
            db.execSQL("UPDATE sqlite_sequence SET seq = 100 WHERE name = 'invoices'")
            for (table in listOf("invoices", "incomes", "products")) db.rawQuery("SELECT * FROM $table", null).use { cursor ->
                cursor.moveToFirst()
                before[table] = cursor.columnNames.map { column -> "$column=${cursor.getString(cursor.getColumnIndexOrThrow(column))}" }
            }
            db.version = 13
        }
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(MIGRATION_13_14).build()
        try {
            val db = database.openHelper.writableDatabase
            for (table in before.keys) db.query("SELECT * FROM $table").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(before[table], cursor.columnNames.filter { it != "taxesJson" }.map { column -> "$column=${cursor.getString(cursor.getColumnIndexOrThrow(column))}" })
                assertEquals("[]", cursor.getString(cursor.getColumnIndexOrThrow("taxesJson")))
            }
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            val id = database.invoiceDao().insertInvoice(Invoice(fecha = 1, proveedor = "New", tipo = InvoiceType.GASTO, total = 1.0, ivaPercent = null).toEntity())
            assertTrue(id > 100)
            assertNull(database.invoiceDao().getInvoiceById(id)!!.ivaPercent)
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun newTaxDataRoundtripsThroughRoomEditingAndRestore() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val taxes = listOf(DocumentTax("GST", 5.0, 100.0, 5.0, TaxTreatment.TAXABLE), DocumentTax("PST", 7.0, 100.0, 7.0, TaxTreatment.TAXABLE))
            val evidence = DocumentEvidence(ScannedDocument(kind = "ticket", issuer = "Synthetic", date = "2026-09-20", currency = "CAD",
                total = 112.0, taxBase = 100.0, taxes = taxes, taxesComplete = true, linesComplete = true, priceBasis = "tax_included",
                lines = listOf(ScannedLine("Basket", 1.0, 112.0, 112.0))))
            val (invoice, products) = evidence.toInvoice("synthetic-taxes", "local")
            val repository = InvoiceRepositoryImpl(database.invoiceDao(), database.productDao(), database)
            val id = repository.insertInvoiceWithProducts(invoice, products)
            repository.updateInvoice(repository.getInvoiceById(id)!!.copy(notas = "Edited note"))
            val backups = BackupDataRepositoryImpl(database.backupDao())
            val snapshot = backups.snapshot()
            backups.replaceAll(snapshot)
            assertEquals(snapshot, backups.snapshot())
            assertEquals(taxes, repository.getInvoiceById(id)!!.taxes)
            assertNull(repository.getInvoiceById(id)!!.ivaPercent)
            assertNull(snapshot.products.single().ivaPercent)
            assertEquals("local", repository.getInvoiceById(id)!!.imagenUri)
        } finally { database.close() }
    }
}
