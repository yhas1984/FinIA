package com.gastos.di

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.domain.model.*
import com.gastos.data.local.entity.toDomain
import com.gastos.local.database.AppDatabase
import com.gastos.storage.WalletPaymentStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WalletCaptureRecoveryTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun isolated(suffix: String): Context = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("qa-wallet-$suffix-$name", mode)
    }
    private fun payment(id: String = "synthetic-event"): WalletPayment = WalletPayment(id, "Synthetic merchant", 20.0, "EUR", 1_791_158_400_000)

    @Test fun failedExpenseRetainsNormalizedEventAndRetriesOnceAfterDatabaseReopen(): Unit = runBlocking {
        val name = "qa-wallet-${UUID.randomUUID()}"
        val testContext = isolated(name)
        var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            var store = WalletPaymentStore(testContext, db)
            store.setEnabled(true)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER qa_wallet_fail BEFORE INSERT ON invoices BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
            assertTrue(runCatching { store.capture(payment()) }.isFailure)
            assertEquals(0, db.invoiceDao().getInvoiceCount())
            val row = db.automationDao().records(WalletPaymentPolicy.CAPTURE_TYPE).single()
            val pending: WalletCaptureRecord = AutomationCodec.json.decodeFromString(row.payload)
            assertEquals(WalletCaptureStatus.FAILED, pending.status)
            assertEquals(1, pending.attempts)
            assertEquals(payment(), pending.payment)
            assertTrue(db.automationDao().records("PAYMENT").isEmpty())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER qa_wallet_fail")
            db.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            store = WalletPaymentStore(testContext, db)
            assertFalse(store.retryPending())
            assertFalse(store.capture(payment()))
            assertEquals(1, db.invoiceDao().getInvoiceCount())
            val evidence = requireNotNull(db.invoiceDao().documentRecords().single().toDomain().evidence)
            assertEquals(setOf("issuer", "date", "currency", "total"), evidence.fieldOrigins.keys)
            assertTrue(evidence.fieldOrigins.values.all { it == DocumentFieldOrigin.WALLET })
            assertTrue(evidence.correctedFields.isEmpty())
            assertEquals(1, db.chatMessageDao().getAllMessages().size)
            assertEquals(1, db.automationDao().records("SYNC").size)
            assertTrue(db.automationDao().records(WalletPaymentPolicy.CAPTURE_TYPE).isEmpty())
            val event: PaymentEvent = AutomationCodec.json.decodeFromString(db.automationDao().records("PAYMENT").single().payload)
            assertEquals(payment().merchant, event.merchant)
            assertEquals(20.0, event.amount!!, 0.0)
            assertEquals("", event.notificationKey)
        } finally {
            testContext.getSharedPreferences(WalletPaymentStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
            db.close(); context.deleteDatabase(name)
        }
    }

    @Test fun optOutPausesDurableNoticeAndNewOptInRequiresExplicitRetry(): Unit = runBlocking {
        val testContext = isolated(UUID.randomUUID().toString())
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val store = WalletPaymentStore(testContext, db)
            store.setEnabled(false)
            assertFalse(store.capture(payment()))
            store.setEnabled(true)
            store.receive(payment(), store.captureSession())
            store.setEnabled(false)
            assertFalse(store.retryPending())
            store.setEnabled(true)
            assertFalse(store.retryPending())
            assertEquals(0, db.invoiceDao().getInvoiceCount())
            store.retry("wallet:pending:synthetic-event")
            assertFalse(store.retryPending())
            assertEquals(1, db.invoiceDao().getInvoiceCount())
        } finally { testContext.getSharedPreferences(WalletPaymentStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit(); db.close() }
    }

    @Test fun simultaneousRedeliveryIsIdempotentButTwoDistinctPaymentsRemainPossible(): Unit = runBlocking {
        val testContext = isolated(UUID.randomUUID().toString())
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val store = WalletPaymentStore(testContext, db)
            store.setEnabled(true)
            val results = coroutineScope { List(3) { async { store.capture(payment()) } }.awaitAll() }
            assertEquals(1, results.count { it })
            assertTrue(store.capture(payment("another-event")))
            assertEquals(2, db.invoiceDao().getInvoiceCount())
            val invoice = db.invoiceDao().documentRecords().first().toDomain()
            val event = PaymentEvent("synthetic-event", "", invoice.documentUuid, payment().occurredAt)
            assertTrue(WalletPaymentPolicy.canUndo(event, invoice, false))
            assertFalse(WalletPaymentPolicy.canUndo(event, invoice.copy(financialRevision = 1), false))
            assertFalse(WalletPaymentPolicy.canUndo(event, invoice, true))
            assertFalse(WalletPaymentPolicy.canUndo(event, invoice.copy(origin = "WALLET_RECEIPT", imagenUri = "synthetic"), false))
        } finally { testContext.getSharedPreferences(WalletPaymentStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit(); db.close() }
    }

    @Test fun revokedPermissionDuringSaveRollsBackWithoutConsumingRetry(): Unit = runBlocking {
        val testContext = isolated(UUID.randomUUID().toString())
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val store = WalletPaymentStore(testContext, db)
            store.setEnabled(true)
            store.receive(payment(), store.captureSession())
            var permissionChecks = 0
            assertFalse(store.retryPending { ++permissionChecks < 3 })
            assertEquals(0, db.invoiceDao().getInvoiceCount())
            assertTrue(db.automationDao().records("PAYMENT").isEmpty())
            val pending: WalletCaptureRecord = AutomationCodec.json.decodeFromString(db.automationDao().records(WalletPaymentPolicy.CAPTURE_TYPE).single().payload)
            assertEquals(0, pending.attempts)
            assertEquals(WalletCaptureStatus.PENDING, pending.status)
            assertFalse(store.retryPending { true })
            assertEquals(1, db.invoiceDao().getInvoiceCount())
        } finally { testContext.getSharedPreferences(WalletPaymentStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit(); db.close() }
    }
}
