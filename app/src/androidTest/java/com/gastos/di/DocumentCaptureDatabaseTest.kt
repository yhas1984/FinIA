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
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15).build()
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

    @Test fun incompleteReceiptSavesDirectlyAndAtomicallyWithHashProtection(): Unit = runBlocking {
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
                total = 100.0, vatPercent = 0.0, number = "TEST-1", issuerTaxId = "B123", linesComplete = false,
                taxBase = 90.0, vatAmount = 50.0, taxesComplete = false,
                lines = listOf(ScannedLine(description = "Partial product", subtotal = 10.0))))
            store.update(started.value.uuid, evidence)
            assertTrue(store.save(started.value.uuid, evidence.copy(document = evidence.document.copy(total = null))) is CaptureSave.UnreadableAmount)
            assertNotNull(store.get(started.value.uuid))
            assertTrue(database.chatMessageDao().getAllMessages().isEmpty())
            val saved = store.save(started.value.uuid, evidence) as CaptureSave.Saved
            assertEquals(100.0, saved.invoice!!.total, 0.0)
            assertEquals(50.0, saved.invoice!!.cuotaIva!!, 0.0)
            assertEquals(evidence.document.lines, saved.invoice!!.evidence!!.document.lines)
            assertNull(store.get(started.value.uuid))
            assertTrue(store.save(started.value.uuid, evidence) is CaptureSave.MissingDraft)
            assertTrue(store.start(sourceUri) is com.gastos.storage.CaptureStart.Duplicate)
            assertEquals(1, database.invoiceDao().getInvoiceCount())
            val receipt = database.chatMessageDao().getAllMessages().single()
            assertEquals("document", receipt.role)
            assertFalse(receipt.includeInContext)
            assertTrue(receipt.visibleText.contains("Synthetic"))
            assertTrue(receipt.visibleText.contains("TEST-1"))
            assertTrue(receipt.visibleText.contains("2026-09-19"))
            val backup = BackupDataRepositoryImpl(database.backupDao())
            backup.replaceAll(backup.snapshot())
            assertEquals(receipt, database.chatMessageDao().getAllMessages().single())
            images.delete(saved.invoice!!.imagenUri)
        } finally { database.close(); source.delete() }
    }

    @Test fun failedChatWriteRollsBackFinancialRecordAndRetryCommitsBothOnce(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val images = InvoiceImageStorage(context)
        val store = DocumentCaptureStore(context, database, images)
        val source = java.io.File(context.filesDir, "invoice_images/chat-rollback.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(4, 5, 6)) }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
        try {
            val draft = (store.start(uri) as com.gastos.storage.CaptureStart.Draft).value
            val evidence = DocumentEvidence(ScannedDocument(kind = "factura_recibida", date = "2026-09-19", currency = "EUR",
                issuer = "Rollback fixture", total = 100.0, vatPercent = 0.0, linesComplete = true,
                priceBasis = "tax_included", lines = listOf(ScannedLine("Item", 1.0, 100.0, 100.0, 0.0))))
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_chat BEFORE INSERT ON chat_messages BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
            try { store.save(draft.uuid, evidence); fail("The transaction must fail") } catch (_: android.database.sqlite.SQLiteException) { }
            assertEquals(0, database.invoiceDao().getInvoiceCount())
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM products").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            assertTrue(database.chatMessageDao().getAllMessages().isEmpty())
            assertNotNull(store.get(draft.uuid))
            context.contentResolver.openInputStream(android.net.Uri.parse(draft.imageUri)).use { assertNotNull(it) }
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_chat")
            val results = listOf(async { store.save(draft.uuid, evidence) }, async { store.save(draft.uuid, evidence) }).awaitAll()
            assertEquals(1, results.filterIsInstance<CaptureSave.Saved>().size)
            assertEquals(1, database.invoiceDao().getInvoiceCount())
            assertEquals(1, database.chatMessageDao().getAllMessages().size)
            images.delete(results.filterIsInstance<CaptureSave.Saved>().single().invoice!!.imagenUri)
        } finally { database.close(); source.delete() }
    }

    @Test fun receiptWithMissingIdentityAndUnfamiliarCurrencySavesWithoutChangingTheSource(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val images = InvoiceImageStorage(context)
        val store = DocumentCaptureStore(context, database, images)
        val source = java.io.File.createTempFile("direct-currency-", ".jpg", context.cacheDir).apply { writeText("synthetic currency receipt") }
        var savedPhoto: String? = null
        var uuid: String? = null
        try {
            val draft = (store.start(android.net.Uri.fromFile(source)) as com.gastos.storage.CaptureStart.Draft).value
            uuid = draft.uuid
            val evidence = DocumentEvidence(ScannedDocument(kind = "ticket", currency = "XYZ", total = 12.50))
            val saved = store.save(draft.uuid, evidence) as CaptureSave.Saved
            savedPhoto = saved.invoice!!.imagenUri
            assertEquals(12.50, saved.invoice!!.total, 0.0)
            assertEquals("XYZ", saved.invoice!!.moneda)
            assertEquals(draft.createdAt, saved.invoice!!.fecha)
            assertNull(saved.invoice!!.evidence!!.document.date)
            assertNull(saved.invoice!!.evidence!!.document.issuer)
            assertTrue(database.chatMessageDao().getAllMessages().single().visibleText.contains("XYZ"))
        } finally {
            uuid?.let { store.discard(it) }
            savedPhoto?.let { images.delete(it) }
            database.close()
            source.delete()
        }
    }

    @Test fun payrollAndIssuedInvoiceHaveOneIncomeReceiptUsingTheSavedAmount(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val images = InvoiceImageStorage(context)
        val store = DocumentCaptureStore(context, database, images)
        try {
            for (kind in listOf("nomina", "factura_emitida")) {
                val source = java.io.File(context.filesDir, "invoice_images/chat-$kind.jpg").apply { parentFile!!.mkdirs(); writeText(kind) }
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
                    val draft = (store.start(uri) as com.gastos.storage.CaptureStart.Draft).value
                    val evidence = DocumentEvidence(ScannedDocument(kind = kind, date = "2026-09-19", currency = "EUR", issuer = "Synthetic $kind",
                        total = 2000.0, gross = if (kind == "nomina") 3000.0 else null, net = if (kind == "nomina") 2000.0 else null,
                        vatPercent = 0.0, linesComplete = false,
                        payroll = if (kind == "nomina") PayrollDetails(totalDeductions = 999.0, linesComplete = false,
                            lines = listOf(PayrollLine("Partial earning", PayrollLineType.EARNING, 12.0))) else null))
                    val saved = store.save(draft.uuid, evidence) as CaptureSave.Saved
                    assertEquals(2000.0, saved.income!!.monto, 0.0)
                    assertTrue(store.save(draft.uuid, evidence) is CaptureSave.MissingDraft)
                    val receipt = database.chatMessageDao().getAllMessages().last()
                    assertTrue(receipt.visibleText.startsWith(context.getString(com.gastos.data.R.string.document_chat_income)))
                    assertTrue(receipt.visibleText.contains("Synthetic $kind"))
                    assertFalse(receipt.visibleText.contains("3.000"))
                    assertFalse(receipt.visibleText.contains("3,000"))
                    assertFalse(receipt.includeInContext)
                    images.delete(saved.income!!.imagenUri)
                } finally { source.delete() }
            }
            assertEquals(2, database.chatMessageDao().getAllMessages().size)
        } finally { database.close() }
    }

    @Test fun documentReceiptDoesNotPreventReplacingAnIncompleteResponse(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = database.chatMessageDao()
            dao.insert(ChatMessageEntity(role = "user", visibleText = "Hello", createdAt = 10))
            dao.insert(ChatMessageEntity(role = "model_incomplete", visibleText = "Partial", createdAt = 20))
            dao.insert(ChatMessageEntity(role = "document", visibleText = "Receipt", includeInContext = false, createdAt = 30))
            dao.replaceLastIncomplete(ChatMessageEntity(role = "model", visibleText = "Complete", createdAt = 40))
            assertEquals(listOf("Hello", "Complete", "Receipt"), dao.getAllMessages().map { it.visibleText })
        } finally { database.close() }
    }

    @Test fun payrollBreakdownAndDateProvenanceSurviveBackupRestore(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val source = ScannedDocument(kind = "nomina", issuer = "Synthetic employer", currency = "EUR", gross = 990.0, net = 890.0,
                payPeriod = "1–31 January 2026", payroll = PayrollDetails(periodStart = "2026-01-01", periodEnd = "2026-01-31",
                    totalDeductions = 100.0, linesComplete = true,
                    lines = listOf(PayrollLine("Salary", PayrollLineType.EARNING, 1000.0),
                        PayrollLine("Adjustment", PayrollLineType.EARNING, -10.0),
                        PayrollLine("Contribution", PayrollLineType.DEDUCTION, 100.0, deductionType = PayrollDeductionType.SOCIAL_SECURITY))))
            val original = DocumentEvidence(source, originalExtraction = "synthetic source").toPayroll("payroll-backup", "photo")
                .copy(driveFileId = "synthetic-drive-photo", driveAccountId = "synthetic-account")
            database.incomeDao().insertIncomeEntity(original.toEntity())
            val backup = BackupDataRepositoryImpl(database.backupDao())
            val before = backup.snapshot()
            backup.replaceAll(before)
            val after = backup.snapshot().incomes.single()
            assertEquals(original.evidence, after.evidence)
            assertEquals(890.0, after.monto, 0.0)
            assertEquals(PayrollDateBasis.PERIOD_END, after.evidence!!.document.payroll!!.dateBasis)
            assertEquals(-10.0, after.evidence!!.document.payroll!!.lines[1].amount!!, 0.0)
            assertEquals("synthetic-drive-photo", after.driveFileId)
            assertEquals("synthetic-account", after.driveAccountId)
        } finally { database.close() }
    }
}
