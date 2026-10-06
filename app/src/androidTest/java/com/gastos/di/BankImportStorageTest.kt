package com.gastos.di

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.data.local.entity.*
import com.gastos.domain.model.*
import com.gastos.local.database.AppDatabase
import com.gastos.repository.impl.BackupDataRepositoryImpl
import com.gastos.storage.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Isolated database and preferences; no test writes reach the user's ledger or Google. */
@RunWith(AndroidJUnit4::class)
class BankImportStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val isolated get() = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("qa-bank-$name", mode)
    }
    private fun db() = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private fun csv(lines: String) = BankCsv.read(("Fecha;Concepto;Importe;ID\n$lines").toByteArray())
    private suspend fun stage(store: BankImportStore, account: BankAccount, lines: String, uniqueId: Boolean = false): String {
        val table = csv(lines)
        return store.stage(table, account, BankCsv.detect(table.headers).copy(transactionId = if (uniqueId) 3 else -1), "synthetic.csv", false)
    }

    @Test fun reimportsRetriesBackupAndRestoreKeepOneMovementAndUnknownTaxes(): Unit = runBlocking {
        val db = db()
        try {
            val store = BankImportStore(isolated, db)
            val account = store.account("Synthetic", "EUR")
            val lines = "04/10/2026;Shop;-20;A\n04/10/2026;Salary;1500;B"
            val batch = stage(store, account, lines)
            assertEquals(2, store.createSafeNew(batch))
            store.transactions().forEach { store.create(it.id, true) }
            assertEquals(batch, stage(store, account, lines))
            assertEquals(1, db.invoiceDao().getInvoiceCount()); assertEquals(1, db.incomeDao().getIncomeCount())
            assertNull(db.invoiceDao().documentRecords().single().ivaPercent)
            val income = db.incomeDao().documentRecords().single()
            assertEquals(0.0, income.totalDevengado, 0.0); assertEquals(0.0, income.totalNeto, 0.0)
            assertEquals(2, db.automationDao().records("SYNC").size)
            val backup = BackupDataRepositoryImpl(db.backupDao(), db)
            val snapshot = backup.snapshot()
            backup.replaceAll(snapshot)
            assertEquals(batch, stage(store, account, lines))
            assertEquals(0, store.createSafeNew(batch))
            assertEquals(snapshot.automation.records.filter { it.type.startsWith("BANK_") }.sortedBy { it.id },
                backup.snapshot().automation.records.filter { it.type.startsWith("BANK_") }.sortedBy { it.id })
        } finally { db.close() }
    }

    @Test fun overlappingStatementsUseUniqueIdsAndRejectConflictingIdsAtomically(): Unit = runBlocking {
        val db = db()
        try {
            val store = BankImportStore(isolated, db); val account = store.account("Synthetic", "EUR")
            stage(store, account, "04/10/2026;Shop;-20;A", true)
            stage(store, account, "04/10/2026;Shop;-20;A\n05/10/2026;Cafe;-2;B", true)
            assertEquals(1, store.transactions().count { it.resolution == BankResolution.DUPLICATE })
            val before = store.transactions()
            assertEquals("BANK_ID_CONFLICT", runCatching { stage(store, account, "06/10/2026;Other;-7;C\n04/10/2026;Shop;-30;A", true) }.exceptionOrNull()?.message)
            assertEquals(before, store.transactions())
        } finally { db.close() }
    }

    @Test fun equalLegitimatePaymentsAndTransfersAreNeverSilentlyMerged(): Unit = runBlocking {
        val db = db()
        try {
            val store = BankImportStore(isolated, db); val account = store.account("Synthetic", "EUR")
            val batch = stage(store, account, "04/10/2026;Cafe;-2;\n04/10/2026;Cafe;-2;\n04/10/2026;Transferencia propia;400;\n04/10/2026;Devolución;10;")
            assertEquals(0, store.createSafeNew(batch))
            store.transactions().filter { it.description == "Cafe" }.forEach { store.create(it.id, true) }
            val transfer = store.transactions().first { it.description.startsWith("Transferencia") }
            store.classify(transfer.id, BankResolution.TRANSFER)
            assertEquals(2, db.invoiceDao().getInvoiceCount()); assertEquals(0, db.incomeDao().getIncomeCount())
            assertEquals(1, store.transactions().count { it.resolution == BankResolution.PENDING })
            store.reopen(transfer.id)
            assertEquals(2, store.transactions().count { it.resolution == BankResolution.PENDING })
        } finally { db.close() }
    }

    @Test fun linkingPreservesMultipleTaxesLegacyIncomeAndDetectsLaterDifferences(): Unit = runBlocking {
        val db = db()
        try {
            val store = BankImportStore(isolated, db); val account = store.account("Synthetic", "EUR")
            stage(store, account, "04/10/2026;Shop;-20;\n04/10/2026;Income;300;")
            val date = store.transactions().first().date
            val taxes = listOf(DocumentTax("IVA", 10.0, 10.0, 1.0, TaxTreatment.TAXABLE), DocumentTax("IVA", 21.0, 5.0, 1.05, TaxTreatment.TAXABLE))
            val invoice = Invoice(fecha = date, proveedor = "Shop", total = 20.0, tipo = InvoiceType.GASTO, ivaPercent = null, taxes = taxes, driveFileId = "remote")
            val id = db.invoiceDao().insertInvoice(invoice.toEntity())
            db.invoiceDao().insertInvoice(invoice.copy(documentUuid = "legacy-income", tipo = InvoiceType.INGRESO, total = 300.0).toEntity())
            val before = db.invoiceDao().documentRecords()
            store.transactions().forEach { row -> store.link(row.id, BankMatching.candidates(row, store.movements()).single()) }
            assertEquals(before, db.invoiceDao().documentRecords())
            assertEquals("INVOICE", store.transactions().first { !it.isExpense }.source)
            db.invoiceDao().updateInvoice(db.invoiceDao().getInvoiceById(id)!!.copy(total = 21.0, financialRevision = 1))
            assertEquals(1, BankQuery.DIFFERENCES.select(store.transactions(), store.movements()).size)
        } finally { db.close() }
    }

    @Test fun interruptedCreationRollsBackMovementSyncAndResolutionTogether(): Unit = runBlocking {
        val db = db()
        try {
            val store = BankImportStore(isolated, db); val account = store.account("Synthetic", "EUR")
            stage(store, account, "04/10/2026;Shop;-20;")
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER bank_fail BEFORE INSERT ON automation_records WHEN NEW.type='BANK_ROW' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
            val row = store.transactions().single()
            assertTrue(runCatching { store.create(row.id, true) }.isFailure)
            assertEquals(0, db.invoiceDao().getInvoiceCount()); assertTrue(db.automationDao().records("SYNC").isEmpty())
            assertEquals(BankResolution.PENDING, store.transactions().single().resolution)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER bank_fail")
            store.create(row.id, true); store.create(row.id, true)
            assertEquals(1, db.invoiceDao().getInvoiceCount())
        } finally { db.close() }
    }

    @Test fun bankFirstThenReceiptOrPayrollKeepsTheSameUuidAndSingleMovement(): Unit = runBlocking {
        val db = db()
        val source = File(context.filesDir, "invoice_images/qa-bank-receipt.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(9,2,6)) }
        val images = InvoiceImageStorage(context)
        val savedPhotos = mutableListOf<String?>()
        try {
            val store = BankImportStore(isolated, db); val account = store.account("Synthetic", "EUR")
            val batch = stage(store, account, "04/10/2026;Shop;-20;\n04/10/2026;Salary;1500;")
            store.createSafeNew(batch)
            val capture = DocumentCaptureStore(context, db, images)
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
            val draft = (capture.start(uri) as CaptureStart.Draft).value
            val evidence = DocumentEvidence(ScannedDocument(kind = "ticket", issuer = "Shop", date = "2026-10-04", currency = "EUR", total = 20.0, vatPercent = 10.0))
            val expense = db.invoiceDao().documentRecords().single().toDomain()
            assertEquals(expense.documentUuid, capture.possibleWalletPayments(evidence).single().documentUuid)
            val result = capture.attachReceipt(draft.uuid, evidence, expense) as CaptureSave.Saved
            savedPhotos.add(result.invoice!!.imagenUri)
            assertEquals(expense.documentUuid, result.invoice!!.documentUuid)
            assertEquals(10.0, result.invoice!!.ivaPercent!!, 0.0)
            source.writeBytes(byteArrayOf(7,1,5))
            val payrollDraft = (capture.start(uri) as CaptureStart.Draft).value
            val payroll = DocumentEvidence(ScannedDocument(kind = "nomina", issuer = "Employer", date = "2026-10-04", currency = "EUR", total = 1500.0, net = 1500.0, gross = 1900.0))
            val income = capture.possibleBankIncomes(payroll).single()
            val incomeResult = capture.attachIncomeReceipt(payrollDraft.uuid, payroll, income) as CaptureSave.Saved
            savedPhotos.add(incomeResult.income!!.imagenUri)
            assertEquals(income.documentUuid, incomeResult.income!!.documentUuid)
            assertEquals(1900.0, incomeResult.income!!.totalDevengado, 0.0)
            assertEquals(1, db.invoiceDao().getInvoiceCount()); assertEquals(1, db.incomeDao().getIncomeCount())
        } finally { savedPhotos.forEach { images.delete(it) }; source.delete(); db.close() }
    }

    @Test fun trustedDocumentReferenceLinksExistingReceiptWithoutChangingItsUuidTaxesOrProducts(): Unit = runBlocking {
        val db = db()
        try {
            val store = BankImportStore(isolated, db)
            val account = store.account("Synthetic", "EUR")
            val table = BankCsv.read("Fecha;Concepto;Importe;Referencia\n04/10/2026;Compra Tienda Norte;-20;F-19".toByteArray())
            val date = requireNotNull(DocumentValidator.parseDate("2026-10-04"))
            val taxes = listOf(DocumentTax("IVA", 10.0, 10.0, 1.0, TaxTreatment.TAXABLE), DocumentTax("IVA", 0.0, 9.0, 0.0, TaxTreatment.ZERO_RATED))
            val receipt = Invoice(documentUuid = "existing-receipt", fecha = date, proveedor = "Tienda Norte", total = 20.0,
                tipo = InvoiceType.GASTO, numeroFactura = "F-19", ivaPercent = null, taxes = taxes, driveFileId = "synthetic-remote")
            val id = db.invoiceDao().insertInvoice(receipt.toEntity())
            db.productDao().insertProducts(listOf(Product(invoiceId = id, descripcion = "Synthetic item", precioUnitario = 10.0, ivaPercent = 0.0).toEntity()))
            val before = BackupDataRepositoryImpl(db.backupDao(), db).snapshot()
            val batch = store.stage(table, account, BankCsv.detect(table.headers).copy(referenceIsDocumentNumber = true), "synthetic.csv", false)
            val progress = mutableListOf<BankProgress>()
            assertEquals(0, store.createSafeNew(batch, progress::add))
            val row = store.transactions().single()
            assertEquals(BankResolution.LINKED, row.resolution)
            assertEquals("existing-receipt", row.documentUuid)
            val after = BackupDataRepositoryImpl(db.backupDao(), db).snapshot()
            assertEquals(before.invoices, after.invoices)
            assertEquals(before.products, after.products)
            assertEquals(listOf(BankProgress(1, 1, linked = 1, created = 0)), progress)
            assertEquals(0, store.createSafeNew(batch))
        } finally { db.close() }
    }

    @Test fun automaticLinkLeavesCompetingBankRowsForAnExplicitChoice(): Unit = runBlocking {
        val db = db()
        try {
            val store = BankImportStore(isolated, db)
            val account = store.account("Synthetic", "EUR")
            val table = BankCsv.read("Fecha;Concepto;Importe;Referencia;ID\n04/10/2026;Tienda Norte;-20;F-19;A\n04/10/2026;Tienda Norte;-20;F-19;B".toByteArray())
            db.invoiceDao().insertInvoice(Invoice(documentUuid = "receipt", fecha = requireNotNull(DocumentValidator.parseDate("2026-10-04")),
                proveedor = "Tienda Norte", total = 20.0, tipo = InvoiceType.GASTO, numeroFactura = "F-19", ivaPercent = null).toEntity())
            val batch = store.stage(table, account, BankCsv.detect(table.headers).copy(referenceIsDocumentNumber = true, transactionId = 4), "synthetic.csv", false)
            assertEquals(0, store.createSafeNew(batch))
            assertEquals(2, store.transactions().count { it.resolution == BankResolution.PENDING })
            assertEquals(1, db.invoiceDao().getInvoiceCount())
        } finally { db.close() }
    }

    @Test fun rectificationChangesOnlyPendingRowsAndPreservesEditedCreatedMovements(): Unit = runBlocking {
        val db = db()
        try {
            val store = BankImportStore(isolated, db)
            val account = store.account("Synthetic", "EUR")
            val table = csv("04/10/2026;First;-20;\n04/10/2026;Edited;-30;")
            val mapping = BankCsv.detect(table.headers)
            val batch = store.stage(table, account, mapping, "synthetic.csv", false)
            val protected = store.transactions().first { it.description == "Edited" }
            store.create(protected.id, true)
            val invoice = db.invoiceDao().documentRecords().single()
            db.invoiceDao().updateInvoice(invoice.copy(notas = "Keep manual edit", financialRevision = 1))
            val saved = db.invoiceDao().documentRecords().single()
            val corrected = mapping.copy(reverseSign = true)
            val preview = store.rectification(table, account, corrected, false)
            assertEquals(2, preview.changed)
            assertEquals(1, preview.protected)
            assertTrue(preview.reversibleCreatedIds.isEmpty())
            assertEquals(batch, store.rectify(table, account, corrected, false))
            val rows = store.transactions()
            assertEquals("20", rows.first { it.description == "First" }.amount)
            assertEquals("-30", rows.first { it.description == "Edited" }.amount)
            assertEquals(BankResolution.CREATED, rows.first { it.description == "Edited" }.resolution)
            assertEquals(saved, db.invoiceDao().documentRecords().single())
            assertEquals(table, store.source(batch))
        } finally { db.close() }
    }

    @Test fun rectificationCanReverseUntouchedCreationBeforeFixingSignWithoutReusingItsUuid(): Unit = runBlocking {
        val db = db()
        try {
            val store = BankImportStore(isolated, db)
            val account = store.account("Synthetic", "EUR")
            val table = csv("04/10/2026;Wrong sign;40;")
            val mapping = BankCsv.detect(table.headers)
            val batch = store.stage(table, account, mapping, "synthetic.csv", false)
            store.createSafeNew(batch)
            val row = store.transactions().single()
            val originalUuid = requireNotNull(row.documentUuid)
            val corrected = mapping.copy(reverseSign = true)
            val preview = store.rectification(table, account, corrected, false)
            assertEquals(listOf(row.id), preview.reversibleCreatedIds)
            assertEquals(0, preview.protected)
            store.undoCreated(store.createdRecord(row.id))
            assertEquals(0, db.incomeDao().getIncomeCount())
            store.rectify(table, account, corrected, false)
            assertEquals(BankResolution.PENDING, store.transactions().single().resolution)
            assertEquals(1, store.createSafeNew(batch))
            assertEquals(1, db.invoiceDao().getInvoiceCount())
            assertEquals(0, db.incomeDao().getIncomeCount())
            assertEquals(40.0, db.invoiceDao().documentRecords().single().total, 0.0)
            assertNotEquals(originalUuid, store.transactions().single().documentUuid)
            val finalUuid = store.transactions().single().documentUuid
            store.create(row.id, true)
            assertEquals(finalUuid, store.transactions().single().documentUuid)
        } finally { db.close() }
    }

    @Test fun undoCreationRejectsEditsAndIdentityReplacementWithoutDeletingAnything(): Unit = runBlocking {
        val db = db()
        try {
            val store = BankImportStore(isolated, db)
            val account = store.account("Synthetic", "EUR")
            val batch = stage(store, account, "04/10/2026;First;-20;")
            store.createSafeNew(batch)
            val row = store.transactions().single()
            val expected = store.createdRecord(row.id)
            val invoice = db.invoiceDao().documentRecords().single()
            db.invoiceDao().updateInvoice(invoice.copy(financialRevision = 1, notas = "Edited"))
            assertTrue(runCatching { store.undoCreated(expected) }.isFailure)
            assertEquals("Edited", db.invoiceDao().documentRecords().single().notas)
            db.invoiceDao().deleteByIdentity(invoice.id, invoice.documentUuid)
            db.invoiceDao().insertInvoice(invoice.copy(documentUuid = "different-document", financialRevision = 0).copy(id = invoice.id))
            assertTrue(runCatching { store.undoCreated(expected) }.isFailure)
            assertEquals("different-document", db.invoiceDao().documentRecords().single().documentUuid)
            assertEquals(BankResolution.CREATED, store.transactions().single().resolution)
        } finally { db.close() }
    }

    @Test fun reversedImportRemainsReversedWhenTheSameFileIsImportedOrProcessedAgain(): Unit = runBlocking {
        val db = db()
        try {
            val store = BankImportStore(isolated, db)
            val account = store.account("Synthetic", "EUR")
            val lines = "04/10/2026;First;-20;"
            val batch = stage(store, account, lines)
            store.createSafeNew(batch)
            val row = store.transactions().single()
            store.undoCreated(store.createdRecord(row.id))
            assertEquals(BankResolution.REVERSED, store.transactions().single().resolution)
            assertEquals(0, db.invoiceDao().getInvoiceCount())
            assertTrue(db.automationDao().records("SYNC").isEmpty())
            assertEquals(batch, stage(store, account, lines))
            assertEquals(0, store.createSafeNew(batch))
            assertTrue(runCatching { store.create(row.id, true) }.isFailure)
            assertEquals(BankResolution.REVERSED, store.transactions().single().resolution)
            assertEquals(0, db.invoiceDao().getInvoiceCount())
        } finally { db.close() }
    }

    @Test fun cancellationAfterOneCommitKeepsProgressAndRetryFinishesWithoutDuplicatingIt(): Unit = runBlocking {
        val db = db()
        try {
            val store = BankImportStore(isolated, db)
            val account = store.account("Synthetic", "EUR")
            val batch = stage(store, account, "04/10/2026;First;-10;\n04/10/2026;Second;-20;\n04/10/2026;Third;-30;")
            val progress = mutableListOf<BankProgress>()
            val job = launch {
                val importJob = requireNotNull(coroutineContext[Job])
                store.createSafeNew(batch) { value ->
                    progress.add(value)
                    if (value.completed == 1) importJob.cancel()
                }
            }
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(listOf(BankProgress(1, 3, created = 1)), progress)
            assertEquals(1, db.invoiceDao().getInvoiceCount())
            val committed = db.invoiceDao().documentRecords().single()
            assertEquals(2, store.transactions().count { it.resolution == BankResolution.PENDING })
            val resumed = BankImportStore(isolated, db)
            val resumedProgress = mutableListOf<BankProgress>()
            assertEquals(2, resumed.createSafeNew(batch, resumedProgress::add))
            assertEquals(listOf(BankProgress(1, 2, created = 1), BankProgress(2, 2, created = 2)), resumedProgress)
            assertEquals(3, db.invoiceDao().getInvoiceCount())
            assertEquals(committed, db.invoiceDao().getByUuid(committed.documentUuid))
            assertEquals(3, db.automationDao().records("SYNC").size)
        } finally { db.close() }
    }

    @Test fun failureInLargeImportRollsBackUnconfirmedChunkAndRetryKeepsCommittedIdentities(): Unit = runBlocking {
        val db = db()
        try {
            val store = BankImportStore(isolated, db)
            val account = store.account("Synthetic", "EUR")
            val lines = (1..100).joinToString("\n") { "04/10/2026;Synthetic $it;-$it;ID-$it" }
            val batch = stage(store, account, lines, true)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER qa_bank_chunk_fail BEFORE INSERT ON invoices WHEN NEW.total = 40 BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
            val progress = mutableListOf<BankProgress>()
            val failure = runCatching {
                store.createSafeNew(batch) { value ->
                    assertTrue(value.completed > (progress.lastOrNull()?.completed ?: 0))
                    assertEquals(100, value.total)
                    assertEquals(value.completed, value.created)
                    val durable = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM invoices").use { cursor ->
                        check(cursor.moveToFirst()); cursor.getInt(0)
                    }
                    assertEquals("Reported progress must only describe committed rows", value.created, durable)
                    progress.add(value)
                }
            }.exceptionOrNull()
            assertNotNull(failure)
            val committed = db.invoiceDao().documentRecords()
            val durableCount = committed.size
            assertTrue("A prior confirmed portion should survive this later failure", durableCount in 1..99)
            assertEquals(durableCount, progress.lastOrNull()?.created ?: 0)
            assertEquals(durableCount, store.transactions().count { it.resolution == BankResolution.CREATED })
            assertEquals(100 - durableCount, store.transactions().count { it.resolution == BankResolution.PENDING })
            assertEquals(durableCount, db.automationDao().records("SYNC").size)
            assertFalse(committed.any { it.total == 40.0 })
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER qa_bank_chunk_fail")
            val resumed = BankImportStore(isolated, db)
            val resumedProgress = mutableListOf<BankProgress>()
            assertEquals(100 - durableCount, resumed.createSafeNew(batch, resumedProgress::add))
            assertEquals(BankProgress(100 - durableCount, 100 - durableCount, created = 100 - durableCount), resumedProgress.last())
            assertEquals(100, db.invoiceDao().getInvoiceCount())
            assertEquals(100, db.automationDao().records("SYNC").size)
            committed.forEach { assertEquals(it, db.invoiceDao().getByUuid(it.documentUuid)) }
            assertEquals(batch, stage(resumed, account, lines, true))
            assertEquals(0, resumed.createSafeNew(batch))
            assertEquals(100, db.invoiceDao().getInvoiceCount())
        } finally { db.close() }
    }
}
