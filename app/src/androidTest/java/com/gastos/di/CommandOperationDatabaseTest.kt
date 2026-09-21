package com.gastos.di

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.local.database.AppDatabase
import com.gastos.domain.model.*
import com.gastos.data.local.entity.*
import com.gastos.repository.impl.*
import com.gastos.storage.CommandOperationStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class CommandOperationDatabaseTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun expense(): Invoice = Invoice(fecha=1, proveedor="Synthetic", tipo=InvoiceType.GASTO,total=20.0,ivaPercent=null)

    @Test fun retryAfterChatTrimOrRecordDeletionNeverRecreatesMovement() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val store = CommandOperationStore(context, database)
            store.begin("operation", "Spent 20")
            val saved = store.commit("operation",expense(),null,emptyList())
            assertEquals("operation",saved.invoice!!.documentUuid)
            database.chatMessageDao().clearAll()
            database.invoiceDao().deleteByIdentity(saved.invoice!!.id,"operation")
            val retry = store.commit("operation",expense(),null,emptyList())
            assertNull(retry.invoice)
            assertEquals(0,database.invoiceDao().getInvoiceCount())
            assertEquals(saved.receipt.visibleText,retry.receipt.visibleText)
        } finally { database.close() }
    }
    @Test fun receiptFailureRollsBackMovementProductsAndOperationResult() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val store = CommandOperationStore(context,database)
            store.begin("operation","Spent 20")
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_receipt BEFORE INSERT ON chat_messages WHEN NEW.role = 'document' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
            assertTrue(runCatching { store.commit("operation",expense(),null,listOf(Product(invoiceId=0,descripcion="Item",precioUnitario=20.0,ivaPercent=null))) }.isFailure)
            assertEquals(0,database.invoiceDao().getInvoiceCount())
            assertEquals("PENDING",store.get("operation")!!.status)
            assertEquals(1,database.chatMessageDao().getAllMessages().size)
        } finally { database.close() }
    }
    @Test fun concurrentRetrySavesOnceButTwoSeparateSubmissionsAreAllowed() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val store = CommandOperationStore(context,database)
            store.begin("first","Spent 20")
            (1..8).map { async(Dispatchers.IO) { store.commit("first",expense(),null,emptyList()) } }.awaitAll()
            assertEquals(1,database.invoiceDao().getInvoiceCount())
            assertEquals(1,database.chatMessageDao().getAllMessages().count { it.role == "document" })
            store.begin("second","Spent 20")
            store.commit("second",expense(),null,emptyList())
            assertEquals(2,database.invoiceDao().getInvoiceCount())
        } finally { database.close() }
    }
    @Test fun legacyIncomeKeepsOriginalTableIdentityTaxAndImageAcrossEdits() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            val id = database.invoiceDao().insertInvoice(expense().copy(tipo=InvoiceType.INGRESO,ivaPercent=10.0,driveFileId="photo",createdAt=15).toEntity())
            val repository = IncomeRepositoryImpl(database.incomeDao(),database)
            val projected = repository.getAllIncomes().first().single()
            assertEquals(-id,projected.id)
            assertEquals("photo",projected.driveFileId)
            repository.updateIncome(projected.copy(concepto="Updated",monto=25.0))
            assertEquals("Updated",database.invoiceDao().getInvoiceById(id)!!.proveedor)
            assertEquals(10.0,database.invoiceDao().getInvoiceById(id)!!.ivaPercent!!,0.0)
            assertEquals(0,database.incomeDao().getIncomeCount())
            repository.deleteIncome(projected)
            assertEquals(0,database.invoiceDao().getInvoiceCount())
        } finally { database.close() }
    }
    @Test fun migration14To15PreservesDataAndCreatesDurableOperations() = runBlocking {
        val name = "command-migration-14-15"
        context.deleteDatabase(name)
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("com.gastos.local.database.AppDatabase/14.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
                val indexes = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (i in 0 until indexes.length()) db.execSQL(indexes.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            db.execSQL("INSERT INTO chat_messages (role,visibleText,includeInContext,createdAt) VALUES ('document','Historic receipt',0,1)")
            db.version = 14
        }
        val database = Room.databaseBuilder(context,AppDatabase::class.java,name).addMigrations(MIGRATION_14_15).build()
        try {
            assertEquals("Historic receipt",database.chatMessageDao().getAllMessages().single().visibleText)
            val store = CommandOperationStore(context,database)
            store.begin("migrated","Spent 20")
            store.commit("migrated",expense(),null,emptyList())
            assertEquals("SAVED",store.get("migrated")!!.status)
        } finally { database.close(); context.deleteDatabase(name) }
    }
    @Test fun documentSnapshotCannotMixAnInvoiceWithProductsFromAnotherEdit() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            val id = database.invoiceDao().insertInvoice(expense().toEntity())
            database.productDao().insertProducts(listOf(Product(invoiceId=id,descripcion="Item",precioUnitario=20.0,subtotal=20.0,ivaPercent=null).toEntity()))
            val snapshots = BackupDataRepositoryImpl(database.backupDao())
            coroutineScope {
                val writer = launch(Dispatchers.IO) {
                    repeat(40) { step -> database.withTransaction {
                        val amount = 20.0 + step
                        database.openHelper.writableDatabase.execSQL("UPDATE invoices SET total = ? WHERE id = ?",arrayOf<Any>(amount,id))
                        database.openHelper.writableDatabase.execSQL("UPDATE products SET subtotal = ?, precioUnitario = ? WHERE invoiceId = ?",arrayOf<Any>(amount,amount,id))
                    } }
                }
                repeat(40) {
                    val snapshot = snapshots.documentSnapshot(false,id)
                    assertEquals(snapshot.invoices.single().total,snapshot.products.single().subtotal,0.0)
                    assertTrue(snapshot.chatMessages.isEmpty())
                }
                writer.join()
            }
        } finally { database.close() }
    }

}
