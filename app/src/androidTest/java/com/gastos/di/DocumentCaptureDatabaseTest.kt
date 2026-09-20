package com.gastos.di

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.data.local.entity.*
import com.gastos.domain.model.*
import com.gastos.local.database.AppDatabase
import com.gastos.repository.impl.*
import com.gastos.storage.DocumentCaptureStore
import com.gastos.storage.CaptureSave
import com.gastos.storage.InvoiceImageStorage
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentCaptureDatabaseTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun invoice(uuid: String = java.util.UUID.randomUUID().toString()): Invoice = Invoice(documentUuid = uuid,
        fecha = DocumentValidator.parseDate("2026-09-19")!!, proveedor = "Synthetic", tipo = InvoiceType.GASTO, total = 121.0,
        numeroFactura = "F-0001", nifEmisor = "B123", ivaPercent = 21.0)

    @Test fun migrationPreservesHistoricalDuplicatesTaxesAndImages() = runBlocking {
        val name = "capture-migration-12-13"
        context.deleteDatabase(name)
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.gastos.local.database.AppDatabase/12.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (i in 0 until indices.length()) db.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            for (id in 1..2) db.execSQL("INSERT INTO invoices (documentUuid,id,fecha,proveedor,tipo,moneda,total,numeroFactura,ivaPercent,irpfPercent,paisCodigo,driveFileId,createdAt,updatedAt) VALUES ('uuid-$id',$id,1,'Synthetic','GASTO','EUR',110,'F-1',10,0,'ES','photo-$id',1,1)")
            db.execSQL("INSERT INTO incomes (documentUuid,id,fecha,concepto,monto,totalDevengado,totalNeto,moneda,ivaPercent,irpfPercent,createdAt,updatedAt) VALUES ('income',1,1,'Synthetic',104,104,104,'EUR',4,0,1,1)")
            db.execSQL("INSERT INTO products (id,invoiceId,descripcion,cantidad,precioUnitario,subtotal,ivaPercent,ivaAmount,createdAt) VALUES (1,1,'Zero rate',1,10,10,0,0,1)")
            db.version = 12
        }
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(MIGRATION_12_13, MIGRATION_13_14).build()
        try {
            assertEquals(2, database.invoiceDao().getInvoiceCount())
            assertEquals(10.0, database.invoiceDao().getInvoiceById(1)!!.ivaPercent!!, 0.0)
            assertEquals("photo-2", database.invoiceDao().getInvoiceById(2)!!.driveFileId)
            assertEquals(4.0, database.incomeDao().getIncomeById(1)!!.ivaPercent!!, 0.0)
            assertNull(database.invoiceDao().getInvoiceById(1)!!.evidenceJson)
            database.documentDraftDao().insert(DocumentDraftEntity("draft", "hash", "photo"))
            assertNotNull(database.documentDraftDao().findHash("hash"))
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun simultaneousInsertAndManualThenScanSaveExactlyOnceWithProducts() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = InvoiceRepositoryImpl(database.invoiceDao(), database.productDao(), database)
            val results = (1..8).map { async(Dispatchers.IO) {
                try { repository.insertInvoiceWithProducts(invoice(), listOf(Product(invoiceId = 0, descripcion = "Item", precioUnitario = 121.0))); true }
                catch (_: DuplicateDocumentException) { false }
            } }.awaitAll()
            assertEquals(1, results.count { it })
            assertEquals(1, database.invoiceDao().getInvoiceCount())
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM products").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            try { repository.insertInvoice(invoice()); fail("Duplicate manual/scanned document") } catch (_: DuplicateDocumentException) { }
        } finally { database.close() }
    }

    @Test fun editsExcludeSelfAndReindexWhileCrossTypeMatchesRequireReview() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val invoices = InvoiceRepositoryImpl(database.invoiceDao(), database.productDao(), database)
            val incomes = IncomeRepositoryImpl(database.incomeDao(), database)
            val original = invoice()
            val saved = original.copy(id = invoices.insertInvoice(original))
            invoices.updateInvoice(saved.copy(numeroFactura = "F-0002"))
            invoices.insertInvoice(invoice()) // Old number is now available.
            val income = invoice().copy(tipo = InvoiceType.INGRESO).toIncome()
            try { incomes.insertIncome(income); fail("Cross-type match") } catch (duplicate: DuplicateDocumentException) {
                assertTrue(duplicate.matches.all { it.strength == DuplicateStrength.POSSIBLE })
            }
            invoices.deleteInvoice(saved)
            assertEquals(1, database.invoiceDao().getInvoiceCount())
        } finally { database.close() }
    }

    @Test fun restoreKeepsEvidenceHistoricalRowsAndPendingDrafts() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val evidence = DocumentEvidence(ScannedDocument(kind = "factura_recibida", number = "F-0001"), sourceSha256 = "photo-hash")
            val row = invoice().copy(evidence = evidence)
            database.invoiceDao().insertInvoice(row.toEntity())
            database.documentDraftDao().insert(DocumentDraftEntity("draft", "draft-hash", "private-draft-photo"))
            val repository = BackupDataRepositoryImpl(database.backupDao())
            val data = repository.snapshot()
            repository.replaceAll(data)
            assertEquals(evidence, database.invoiceDao().getInvoiceById(1)!!.toDomain().evidence)
            assertNotNull(database.documentDraftDao().get("draft"))
            assertEquals(1, DocumentGuard(database).findHash("photo-hash").size)
            assertTrue(DocumentGuard(database).find(invoice().documentIdentity()).isNotEmpty())
        } finally { database.close() }
    }

    @Test fun draftPhotoSurvivesReviewAndSaveIsAtomicWithHashProtection(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val images = InvoiceImageStorage(context)
        val store = DocumentCaptureStore(context, database, images)
        val source = java.io.File(context.filesDir, "invoice_images/capture-synthetic.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        val sourceUri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
        try {
            val started = store.start(sourceUri) as com.gastos.storage.CaptureStart.Draft
            assertFalse(started.resumed)
            assertTrue((store.start(sourceUri) as com.gastos.storage.CaptureStart.Draft).resumed)
            val evidence = DocumentEvidence(ScannedDocument(kind = "factura_recibida", date = "2026-09-19", currency = "EUR", issuer = "Synthetic",
                total = 100.0, vatPercent = 0.0, number = "TEST-1", issuerTaxId = "B123", linesComplete = true))
            store.update(started.value.uuid, evidence)
            assertTrue(store.save(started.value.uuid, evidence.copy(document = evidence.document.copy(date = null))) is CaptureSave.Review)
            assertNotNull(store.get(started.value.uuid))
            val saved = store.save(started.value.uuid, evidence) as CaptureSave.Saved
            assertNull(store.get(started.value.uuid))
            assertTrue(store.save(started.value.uuid, evidence) is CaptureSave.MissingDraft)
            assertTrue(store.start(sourceUri) is com.gastos.storage.CaptureStart.Duplicate)
            assertEquals(1, database.invoiceDao().getInvoiceCount())
            images.delete(saved.invoice!!.imagenUri)
        } finally { database.close(); source.delete() }
    }
}
