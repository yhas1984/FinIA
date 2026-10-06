package com.gastos.di

import android.content.Context
import android.content.ContextWrapper
import android.graphics.pdf.PdfDocument
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.data.local.entity.*
import com.gastos.domain.model.*
import com.gastos.local.database.AppDatabase
import com.gastos.repository.impl.BackupDataRepositoryImpl
import com.gastos.storage.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AutomationIntegrationTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val isolatedContext: Context get() = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("qa-automation-$name", mode)
    }
    @Test fun migration15To17PreservesFinancialColumnsAndBindsParentScopedCategories() = runBlocking {
        val name = "qa-automation-migration"
        context.deleteDatabase(name)
        val before = mutableMapOf<String, List<String?>>()
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("com.gastos.local.database.AppDatabase/15.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                for (i in 0 until (indices?.length() ?: 0)) db.execSQL(indices!!.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            db.execSQL("INSERT INTO invoices (id,documentUuid,fecha,proveedor,tipo,moneda,total,ivaPercent,irpfPercent,paisCodigo,categoria,subcategoria,driveFileId,createdAt,updatedAt) VALUES (7,'qa-expense',1,'Synthetic','GASTO','EUR',110,10,0,'ES','Forza','Gasolina','remote',1,1)")
            db.execSQL("INSERT INTO incomes (id,documentUuid,fecha,concepto,monto,totalDevengado,totalNeto,moneda,irpfPercent,categoria,subcategoria,createdAt,updatedAt) VALUES (8,'qa-income',1,'Synthetic',450,0,0,'EUR',0,'Forza','Gasolina',1,1)")
            for (table in listOf("invoices", "incomes")) db.rawQuery("SELECT * FROM $table", null).use { cursor ->
                cursor.moveToFirst(); before[table] = cursor.columnNames.map { "$it=${cursor.getString(cursor.getColumnIndexOrThrow(it))}" }
            }
            db.version = 15
        }
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(MIGRATION_15_16, MIGRATION_16_17).build()
        try {
            val db = database.openHelper.writableDatabase
            for (table in before.keys) db.query("SELECT * FROM $table").use { cursor ->
                cursor.moveToFirst()
                assertEquals(before[table], cursor.columnNames.filter { it !in setOf("categoryId","subcategoryId","sourceMimeType","sourceName","origin","manualAmountAdjusted","financialRevision") }.map { "$it=${cursor.getString(cursor.getColumnIndexOrThrow(it))}" })
            }
            val expense = database.invoiceDao().documentRecords().single()
            val income = database.incomeDao().documentRecords().single()
            assertNotEquals(expense.categoryId, income.categoryId)
            assertEquals(expense.categoryId, database.automationDao().categories().first { it.id == expense.subcategoryId }.parentId)
            assertEquals(0, database.automationDao().limits().size)
        } finally { database.close(); context.deleteDatabase(name) }
    }
    @Test fun categoryRenameArchiveAndRulesPreserveIdentityAndStoredTaxes() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val catalog = CategoryCatalog(db)
            val parent = catalog.create(DocumentKind.EXPENSE, "Forza")
            assertEquals(parent.id, catalog.create(DocumentKind.EXPENSE, "  FORZA  ").id)
            val child = catalog.create(DocumentKind.EXPENSE, "Gasolina", parent.id)
            val record = catalog.assign(Invoice(fecha=1, proveedor="Synthetic", tipo=InvoiceType.GASTO, total=110.0, ivaPercent=10.0, categoria="Forza", subcategoria="Gasolina"))
            val id = db.invoiceDao().insertInvoice(record.toEntity())
            catalog.putRule(CategoryRule("rule", DocumentKind.EXPENSE, "Synthetic", parent.id, child.id))
            catalog.rename(parent.id, "Furgoneta")
            val renamed = db.invoiceDao().getInvoiceById(id)!!
            assertEquals(parent.id, renamed.categoryId)
            assertEquals("Furgoneta", renamed.categoria)
            assertEquals(10.0, renamed.ivaPercent!!, 0.0)
            catalog.archive(parent.id, true)
            assertEquals(1, db.invoiceDao().getInvoiceCount())
            assertFalse(AutomationCodec.json.decodeFromString<CategoryRule>(db.automationDao().record("rule")!!.payload).enabled)
            assertEquals(child.id, catalog.assign(renamed.toDomain().copy(notas = "Unrelated edit")).subcategoryId)
            assertTrue(runCatching { catalog.create(DocumentKind.EXPENSE, "New child", parent.id) }.isFailure)
        } finally { db.close() }
    }
    @Test fun chatCorrectionsRequireAChoiceAndRollbackWhenConfirmationFails(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val first = Invoice(documentUuid="qa-first", fecha=1, proveedor="Synthetic matching store", tipo=InvoiceType.GASTO, total=20.0, ivaPercent=null)
            val second = first.copy(documentUuid="qa-second")
            val firstId = db.invoiceDao().insertInvoice(first.toEntity())
            db.invoiceDao().insertInvoice(second.toEntity())
            val operations = CommandOperationStore(isolatedContext, db)
            val mutations = FinancialMutationStore(isolatedContext, db)
            val catalog = CategoryCatalog(db)
            val commands = com.gastos.feature.chatbot.AutomationCommands(context, db, catalog, mutations, operations)
            operations.begin("qa-correction", "Change the matching store to Forza")
            val outcome = commands.execute("qa-correction", """{"action":"update_movement","kind":"EXPENSE","description":"matching store","patch":{"category":"Forza","subcategory":"Gasolina"}}""")
            val pending = requireNotNull(outcome.pending)
            assertEquals(2, pending.choices.size)
            assertNull(db.invoiceDao().getInvoiceById(firstId)!!.categoryId)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_confirmation BEFORE INSERT ON chat_messages WHEN NEW.role='model' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
            assertTrue(runCatching { commands.apply(pending, first.documentUuid) }.isFailure)
            assertNull(db.invoiceDao().getInvoiceById(firstId)!!.categoryId)
            assertTrue(db.automationDao().records("MUTATION").isEmpty())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_confirmation")
            val applied = commands.apply(pending, first.documentUuid)
            assertEquals("Forza", db.invoiceDao().getInvoiceById(firstId)!!.categoria)
            assertEquals("SAVED", operations.get("qa-correction")!!.status)
            assertTrue(db.automationDao().records("RULE").isEmpty())
            commands.saveRule(requireNotNull(applied.suggestedRule))
            assertEquals(1, db.automationDao().records("RULE").size)
            assertEquals(applied.message, commands.apply(pending, first.documentUuid).message)
        } finally { db.close() }
    }
    @Test fun atomicCorrectionsRetryUndoAndLaterEditConflictsKeepOriginalBreakdown() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val taxes = listOf(DocumentTax("IVA",10.0,100.0,10.0,TaxTreatment.TAXABLE))
            val record = Invoice(documentUuid="qa-record", fecha=1, proveedor="Synthetic", tipo=InvoiceType.GASTO, total=110.0, ivaPercent=10.0, taxes=taxes)
            val id = db.invoiceDao().insertInvoice(record.toEntity())
            val store = FinancialMutationStore(isolatedContext, db)
            val first = store.update("qa-op", record.documentUuid, MovementPatch(amount=120.0), 0)
            assertEquals(first, store.update("qa-op", record.documentUuid, MovementPatch(amount=120.0), 0))
            val changed = db.invoiceDao().getInvoiceById(id)!!.toDomain()
            assertEquals(120.0, changed.total, 0.0)
            assertTrue(changed.manualAmountAdjusted)
            assertEquals(taxes, changed.taxes)
            assertEquals(1, db.chatMessageDao().getAllMessages().size)
            assertEquals(1, db.automationDao().records("SYNC").size)
            store.undo("qa-undo", "qa-op")
            assertEquals(110.0, db.invoiceDao().getInvoiceById(id)!!.total, 0.0)
            assertEquals(store.undo("qa-undo", "qa-op"), store.undo("qa-undo", "qa-op"))
            store.update("qa-next", record.documentUuid, MovementPatch(notes="A"))
            store.update("qa-later", record.documentUuid, MovementPatch(notes="B"))
            assertTrue(runCatching { store.undo("qa-conflict", "qa-next") }.isFailure)
            assertEquals("B", db.invoiceDao().getInvoiceById(id)!!.notas)
        } finally { db.close() }
    }

    @Test fun undoRefusesAnIdentityOccupiedAfterCorrectionWithoutChangingRecordsOrHistory(): Unit = runBlocking {
        for (isIncome in listOf(false, true)) {
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                val document = ScannedDocument(kind = if (isIncome) "factura_emitida" else "factura_recibida",
                    number = "SYNTHETIC-UNDO", issuer = "Original synthetic merchant", date = "2026-10-05", currency = "EUR", total = 20.0)
                val original = DocumentEvidence(document).toInvoice("synthetic-original", "synthetic", 1).first.copy(imagenUri = null)
                if (isIncome) db.incomeDao().insertIncomeEntity(original.toIncome().toEntity())
                else db.invoiceDao().insertInvoice(original.toEntity())
                val mutations = FinancialMutationStore(isolatedContext, db)
                val correction = mutations.update("synthetic-correction", original.documentUuid, MovementPatch(merchant = "Different synthetic merchant"), 0)
                val other = original.copy(documentUuid = "synthetic-other")
                // The corrected merchant made this a distinct valid document at insertion time.
                if (isIncome) com.gastos.repository.impl.IncomeRepositoryImpl(db.incomeDao(), db).insertIncome(other.toIncome())
                else com.gastos.repository.impl.InvoiceRepositoryImpl(db.invoiceDao(), db.productDao(), db).insertInvoice(other)
                val invoices = db.invoiceDao().documentRecords()
                val incomes = db.incomeDao().documentRecords()
                val history = db.chatMessageDao().getAllMessages()
                val pendingSync = db.automationDao().records("SYNC")
                val mutation = db.automationDao().record("mutation:synthetic-correction")
                val failure = runCatching { mutations.undo("synthetic-conflicting-undo", correction.id) }.exceptionOrNull()
                assertTrue(failure is DuplicateDocumentException)
                assertEquals(invoices, db.invoiceDao().documentRecords())
                assertEquals(incomes, db.incomeDao().documentRecords())
                assertEquals(history, db.chatMessageDao().getAllMessages())
                assertEquals(pendingSync, db.automationDao().records("SYNC"))
                assertEquals(mutation, db.automationDao().record("mutation:synthetic-correction"))
                assertFalse(AutomationCodec.json.decodeFromString<FinancialMutation>(requireNotNull(mutation).payload).undone)
                assertNull(db.automationDao().record("undo:synthetic-conflicting-undo"))
            } finally { db.close() }
        }
    }
    @Test fun walletIsOptInIdempotentAndReceiptAssociationKeepsOneUuid(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val source = File(context.filesDir, "invoice_images/qa-receipt.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1,2,3)) }
        val testContext = isolatedContext
        val payments = WalletPaymentStore(testContext, db)
        val previousEnabled = payments.enabled
        try {
            val now = System.currentTimeMillis()
            val payment = WalletPayment("qa-event", "Synthetic",20.0,"EUR",now)
            payments.setEnabled(false)
            assertFalse(payments.capture(payment))
            payments.setEnabled(true)
            assertTrue(payments.capture(payment))
            assertFalse(payments.capture(payment))
            val wallet = db.invoiceDao().documentRecords().single().toDomain()
            assertNull(wallet.ivaPercent)
            assertEquals("",wallet.paisCodigo)
            assertNotNull(wallet.notas)
            val images = InvoiceImageStorage(context)
            val store = DocumentCaptureStore(context, db, images)
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
            val started = store.start(uri) as CaptureStart.Draft
            val date = java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.ROOT).format(java.util.Date(now))
            val evidence = DocumentEvidence(ScannedDocument(kind="ticket",issuer="Synthetic",date=date,currency="EUR",total=20.0))
            val result = store.attachReceipt(started.value.uuid, evidence, wallet) as CaptureSave.Saved
            assertEquals(wallet.documentUuid,result.invoice!!.documentUuid)
            assertEquals(1,db.invoiceDao().getInvoiceCount())
            assertEquals("WALLET_RECEIPT",result.invoice!!.origin)
            assertTrue(runCatching { payments.undo("wallet:qa-event") }.isFailure)
            images.delete(result.invoice!!.imagenUri)
        } finally { payments.setEnabled(previousEnabled); source.delete(); db.close() }
    }

    @Test fun walletThenReceiptThenBankStatementKeepsOneExpenseAndAllSourceEvidence(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val prefix = "qa-three-sources-${java.util.UUID.randomUUID()}"
        val preferenceNames = mutableSetOf<String>()
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val isolatedName = "$prefix-$name"
                synchronized(preferenceNames) { preferenceNames.add(isolatedName) }
                return super.getSharedPreferences(isolatedName, mode)
            }
        }
        val payments = WalletPaymentStore(isolated, db)
        val images = InvoiceImageStorage(context)
        val capture = DocumentCaptureStore(isolated, db, images)
        val source = File.createTempFile("three-sources-", ".jpg", File(context.cacheDir, "camera").apply { mkdirs() })
            .apply { writeText("Synthetic three-source receipt") }
        var draftUuid: String? = null
        var savedPhoto: String? = null
        try {
            val payment = WalletPayment("synthetic-three-source", "Tienda Prueba", 33.1, "EUR", requireNotNull(DocumentValidator.parseDate("2026-10-05")))
            payments.setEnabled(true)
            assertTrue(payments.capture(payment))
            val originalUuid = db.invoiceDao().documentRecords().single().documentUuid
            FinancialMutationStore(isolated, db).update("synthetic-note", originalUuid, MovementPatch(notes = "Manual note kept across sources"))
            val taxes = listOf(DocumentTax("IVA", 21.0, 10.0, 2.1, TaxTreatment.TAXABLE),
                DocumentTax("IVA", 10.0, 10.0, 1.0, TaxTreatment.TAXABLE), DocumentTax("IVA", 0.0, 10.0, 0.0, TaxTreatment.ZERO_RATED))
            val evidence = DocumentEvidence(ScannedDocument(kind = "ticket", issuer = "Tienda Prueba", date = "2026-10-05",
                currency = "EUR", total = 33.1, number = "SYNTHETIC-F-3", taxes = taxes, taxesComplete = true))
            val candidate = capture.possibleWalletPayments(evidence).single()
            assertEquals(originalUuid, candidate.documentUuid)
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
            val draft = (capture.start(uri) as CaptureStart.Draft).value
            draftUuid = draft.uuid
            val saved = (capture.attachReceipt(draft.uuid, evidence, candidate) as CaptureSave.Saved).invoice!!
            savedPhoto = saved.imagenUri
            assertEquals(originalUuid, saved.documentUuid)
            assertEquals(taxes, saved.taxes)
            assertEquals("Manual note kept across sources", saved.notas)
            assertEquals(DocumentFieldOrigin.WALLET, saved.evidence!!.fieldOrigins["total"])
            assertEquals(DocumentFieldOrigin.EXTRACTED, saved.evidence!!.fieldOrigins["taxes"])
            val afterReceipt = db.invoiceDao().documentRecords().single()
            val eventBefore = db.automationDao().record("wallet:${payment.eventId}")!!
            val bank = BankImportStore(isolated, db)
            val account = bank.account("Synthetic", "EUR")
            val table = BankCsv.read("Fecha;Concepto;Importe;Referencia;ID\n09/10/2026;Compra Tienda Prueba;-33,10;SYNTHETIC-F-3;SYNTHETIC-BANK-3".toByteArray())
            val mapping = BankCsv.detect(table.headers).copy(transactionId = 4, referenceIsDocumentNumber = true)
            val batch = bank.stage(table, account, mapping, "synthetic.csv", false)
            assertEquals(0, bank.createSafeNew(batch))
            val bankEvidence = bank.transactions().single()
            assertEquals(BankResolution.LINKED, bankEvidence.resolution)
            assertEquals(originalUuid, bankEvidence.documentUuid)
            assertEquals("SYNTHETIC-BANK-3", bankEvidence.bankId)
            assertEquals("-33.1", bankEvidence.amount)
            assertEquals("2026-10-09", bankEvidence.bookingDate)
            val walletEvidence = AutomationCodec.json.decodeFromString<PaymentEvent>(eventBefore.payload)
            assertEquals(originalUuid, walletEvidence.documentUuid)
            assertEquals(payment.occurredAt, walletEvidence.postedAt)
            assertEquals(afterReceipt, db.invoiceDao().documentRecords().single())
            assertEquals(eventBefore, db.automationDao().record("wallet:${payment.eventId}"))
            assertFalse(WalletPaymentPolicy.canUndo(walletEvidence, saved, bankLinked = true))
            assertEquals("PAYMENT_CHANGED", runCatching { payments.undo("wallet:${payment.eventId}") }.exceptionOrNull()?.message)
            assertEquals(afterReceipt, db.invoiceDao().documentRecords().single())
            assertEquals(eventBefore, db.automationDao().record("wallet:${payment.eventId}"))
            assertFalse(payments.capture(payment))
            assertTrue(capture.start(uri) is CaptureStart.Duplicate)
            assertEquals(batch, bank.stage(table, account, mapping, "synthetic.csv", false))
            assertEquals(0, bank.createSafeNew(batch))
            assertEquals(1, db.invoiceDao().getInvoiceCount())
            assertEquals(0, db.incomeDao().getIncomeCount())
        } finally {
            draftUuid?.let { capture.discard(it) }
            savedPhoto?.let { images.delete(it) }
            source.delete()
            db.close()
            synchronized(preferenceNames) { preferenceNames.toList() }.forEach(context::deleteSharedPreferences)
        }
    }
    @Test fun pdfSandboxCountsAndRendersWithoutChangingOriginalBytes() = runBlocking {
        val original = File(context.cacheDir,"qa-two-pages.pdf")
        val preview = File(context.cacheDir,"qa-page.png")
        val report = File(context.filesDir, "consolidation-viewer/pdf-render-report.json").apply { parentFile!!.mkdirs(); delete() }
        try {
            val service = context.packageManager.getServiceInfo(android.content.ComponentName(context, PdfSandboxService::class.java), 0)
            assertFalse(service.exported)
            assertTrue(service.flags and android.content.pm.ServiceInfo.FLAG_ISOLATED_PROCESS != 0)
            val pdf = PdfDocument()
            try { repeat(2) { page ->
                val documentPage=pdf.startPage(PdfDocument.PageInfo.Builder(300,400,page+1).create())
                documentPage.canvas.drawText("SYNTHETIC ${page+1}",20f,30f,android.graphics.Paint())
                pdf.finishPage(documentPage)
            }; original.outputStream().use(pdf::writeTo) } finally { pdf.close() }
            val bytes=original.readBytes()
            assertEquals(2,PdfSandbox.pageCount(context,original))
            val renderStarted = android.os.SystemClock.elapsedRealtime()
            PdfSandbox.render(context,original,1,preview)
            val renderMillis = android.os.SystemClock.elapsedRealtime() - renderStarted
            assertTrue(preview.length()>0)
            val signature = preview.inputStream().use { input -> ByteArray(8).also { assertEquals(8, input.read(it)) } }
            assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a), signature)
            val options = android.graphics.BitmapFactory.Options()
            val rendered = requireNotNull(android.graphics.BitmapFactory.decodeFile(preview.absolutePath, options))
            assertEquals("image/png", options.outMimeType)
            try { assertEquals(1200, rendered.width); assertEquals(1600, rendered.height) }
            finally { rendered.recycle() }
            assertArrayEquals(bytes,original.readBytes())
            original.writeText("not a pdf")
            assertTrue(runCatching { PdfSandbox.pageCount(context,original) }.isFailure)
            report.writeText(org.json.JSONObject().put("result", "PASS").put("recordedAtEpochMs", System.currentTimeMillis())
                .put("fixture", "synthetic-two-page-pdf").put("renderMillis", renderMillis)
                .put("mimeType", options.outMimeType).put("pngSignatureHex", signature.joinToString("") { "%02x".format(it) })
                .put("width", 1200).put("height", 1600).put("isolatedProcess", true).put("originalUnchanged", true).toString(2))
        } finally { original.delete();preview.delete() }
    }
    @Test fun monthlyLimitsRepeatAndPartialTotalsStaySeparateFromIncome(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val catalog = CategoryCatalog(db)
            val category = catalog.create(DocumentKind.EXPENSE, "Synthetic fuel")
            val incomeCategory = catalog.create(DocumentKind.INCOME, "Synthetic income")
            val rates = object : com.gastos.repository.ExchangeRateProvider {
                override val rates = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Double>>(emptyMap())
                override val lastUpdated = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
                override suspend fun refresh() = Unit
                override fun convert(amount: Double, from: String, to: String): Double? = if (from == to) amount else null
            }
            val limits = MonthlyLimitStore(db, rates)
            limits.put(MonthlyLimit("ignored", category.id, "2026-09", 100.0, "EUR", repeat = true))
            assertTrue(runCatching { limits.put(MonthlyLimit("bad", incomeCategory.id, "2026-10", 100.0, "EUR")) }.isFailure)
            val date = DocumentValidator.parseDate("2026-10-01")!!
            for ((kind, amount, currency) in listOf(Triple(InvoiceType.GASTO, 20.0, "EUR"), Triple(InvoiceType.GASTO, 30.0, "USD"), Triple(InvoiceType.INGRESO, 500.0, "EUR"))) {
                db.invoiceDao().insertInvoice(Invoice(fecha = date, proveedor = "Synthetic", tipo = kind, total = amount, moneda = currency, categoryId = category.id).toEntity())
            }
            val progress = kotlinx.coroutines.withTimeout(5_000) { limits.progress("2026-10").first().single() }
            assertEquals(20.0, progress.spent!!, 0.0)
            assertEquals(1, progress.excluded)
            assertTrue(progress.partial)
            assertEquals(2, db.automationDao().limits().size)
            limits.markNotified(progress.limit)
            assertTrue(kotlinx.coroutines.withTimeout(5_000) { limits.limits.first() }.first { it.month == "2026-10" }.notified)
        } finally { db.close() }
    }
    @Test fun backupPreservesCatalogRulesMutationsAndPdfReferences() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val catalog = CategoryCatalog(db)
            val parent = catalog.create(DocumentKind.EXPENSE,"Forza")
            catalog.putRule(CategoryRule("qa-rule",DocumentKind.EXPENSE,"Synthetic",parent.id))
            val record = catalog.assign(Invoice(fecha=1,proveedor="Synthetic",tipo=InvoiceType.GASTO,total=20.0,ivaPercent=null,categoria="Forza",sourceMimeType="application/pdf",sourceName="synthetic.pdf",driveFileId="qa-remote"))
            db.invoiceDao().insertInvoice(record.toEntity())
            val backups = BackupDataRepositoryImpl(db.backupDao(),db)
            val snapshot = backups.snapshot()
            backups.replaceAll(snapshot)
            val restored = backups.snapshot()
            assertEquals(snapshot.invoices,restored.invoices)
            assertEquals(parent.id,restored.invoices.single().categoryId)
            assertEquals("qa-rule",AutomationCodec.json.decodeFromString<CategoryRule>(restored.automation.records.first { it.type == "RULE" }.payload).id)
            assertEquals("application/pdf",restored.invoices.single().sourceMimeType)
        } finally { db.close() }
    }
}
