package com.gastos.di

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.local.database.AppDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Migration11To12Test {
    @Test fun preservesIdsProductsTaxAndRemoteReferencesAndGuardsLateUpdates() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "migration-11-12-synthetic"
        context.deleteDatabase(name)
        val schema = instrumentation.context.assets.open("com.gastos.local.database.AppDatabase/11.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (i in 0 until indices.length()) db.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            db.execSQL("INSERT INTO invoices (id,fecha,proveedor,tipo,moneda,total,ivaPercent,irpfPercent,paisCodigo,driveFileId,driveWebViewLink,createdAt,updatedAt) VALUES (7,1,'Synthetic','GASTO','EUR',110,10,0,'ES','legacy-file','https://drive.google.com/file/d/legacy-file/view',1,2)")
            db.execSQL("INSERT INTO products (id,invoiceId,descripcion,cantidad,precioUnitario,subtotal,ivaPercent,ivaAmount,createdAt) VALUES (8,7,'Zero tax',1,10,10,0,0,1)")
            db.execSQL("INSERT INTO incomes (id,fecha,concepto,monto,totalDevengado,totalNeto,moneda,ivaPercent,irpfPercent,imagenUri,createdAt,updatedAt) VALUES (7,1,'Synthetic income',104,104,104,'EUR',4,0,'content://synthetic/income',1,2)")
            db.version = 11
        }
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14).build()
        try {
            val invoice = database.invoiceDao().getInvoiceById(7)!!
            val income = database.incomeDao().getIncomeById(7)!!
            assertNotEquals(invoice.documentUuid, income.documentUuid)
            UUID.fromString(invoice.documentUuid)
            UUID.fromString(income.documentUuid)
            assertEquals(10.0, invoice.ivaPercent!!, 0.0)
            assertEquals(4.0, income.ivaPercent!!, 0.0)
            assertEquals("legacy-file", invoice.driveFileId)
            assertTrue(income.driveUploadPending)
            database.openHelper.writableDatabase.query("SELECT invoiceId,ivaPercent FROM products WHERE id=8").use {
                assertTrue(it.moveToFirst()); assertEquals(7L, it.getLong(0)); assertEquals(0.0, it.getDouble(1), 0.0)
            }
            assertEquals(1, database.invoiceDao().updateImageSync(7, invoice.documentUuid, null,
                "verified-file", "https://drive.google.com/file/d/verified-file/view", false, "account-a", "hash", null))
            database.invoiceDao().updatePreservingImageState(invoice.copy(total = 104.0, ivaPercent = 4.0))
            val edited = database.invoiceDao().getInvoiceById(7)!!
            assertEquals("verified-file", edited.driveFileId)
            assertEquals(4.0, edited.ivaPercent!!, 0.0)
            assertEquals(0, database.invoiceDao().updateImageSync(7, UUID.randomUUID().toString(), null,
                "wrong-file", null, false, "account-b", null, null))
            database.invoiceDao().deleteByIdentity(7, UUID.randomUUID().toString())
            assertNotNull(database.invoiceDao().getInvoiceById(7))
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
