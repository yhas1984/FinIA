package com.gastos.storage

import android.content.Context
import androidx.room.withTransaction
import com.gastos.data.local.entity.*
import com.gastos.domain.model.*
import com.gastos.local.database.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WalletPaymentStore @Inject constructor(@ApplicationContext private val context: Context, private val database: AppDatabase) {
    private val settings get() = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    val enabled: Boolean get() = settings.getBoolean("enabled", false)
    @Synchronized
    fun setEnabled(enabled: Boolean) {
        val editor = settings.edit().putBoolean("enabled", enabled)
        // Each opt-in starts a new session. Turning capture off never imports a backlog later.
        if (this.enabled != enabled || settings.getString("session", null) == null) editor.putString("session", java.util.UUID.randomUUID().toString())
        check(editor.commit()) { "WALLET_SETTINGS_FAILED" }
    }
    @Synchronized
    fun captureSession(): String {
        if (!enabled) return ""
        if (settings.getString("session", null) == null) setEnabled(true)
        return settings.getString("session", "").orEmpty()
    }

    suspend fun receive(payment: WalletPayment, session: String): Boolean = database.withTransaction {
        WalletPaymentPolicy.validate(payment)
        // This entry point receives an already accepted WorkManager event. A later
        // opt-out pauses registration, but must not erase that normalized evidence.
        if (session.isBlank()) return@withTransaction false
        val dao = database.automationDao()
        if (dao.record("wallet:${payment.eventId}") != null || dao.record(pendingId(payment.eventId)) != null) return@withTransaction false
        putCapture(WalletCaptureRecord(payment, session))
        true
    }

    /** Keep normalized evidence durable even when the financial transaction fails. */
    suspend fun capture(payment: WalletPayment): Boolean {
        if (!enabled) return false
        receive(payment, captureSession())
        return process(pendingId(payment.eventId))
    }

    suspend fun retryPending(hasAccess: () -> Boolean = { true }): Boolean {
        if (!enabled || !hasAccess()) return false
        val records = pendingCaptures()
        records.filter { it.session == captureSession() && it.status != WalletCaptureStatus.IGNORED && it.attempts < WalletPaymentPolicy.MAX_ATTEMPTS }.forEach {
            try { process(pendingId(it.payment.eventId), hasAccess) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The normalized event and stable error remain available. */ }
        }
        return enabled && hasAccess() && pendingCaptures().any { it.session == captureSession() && it.status != WalletCaptureStatus.IGNORED && it.attempts < WalletPaymentPolicy.MAX_ATTEMPTS }
    }

    suspend fun retry(id: String) = database.withTransaction {
        require(enabled) { "WALLET_DISABLED" }
        val record: WalletCaptureRecord = AutomationCodec.json.decodeFromString(requireNotNull(database.automationDao().record(id)).payload)
        putCapture(record.copy(session = captureSession(), status = WalletCaptureStatus.PENDING, attempts = 0, error = null))
    }

    suspend fun ignore(id: String) = database.withTransaction {
        val record: WalletCaptureRecord = AutomationCodec.json.decodeFromString(requireNotNull(database.automationDao().record(id)).payload)
        putCapture(record.copy(status = WalletCaptureStatus.IGNORED))
    }

    private suspend fun pendingCaptures(): List<WalletCaptureRecord> = database.automationDao().records(WalletPaymentPolicy.CAPTURE_TYPE)
        .map { AutomationCodec.json.decodeFromString(it.payload) }

    private suspend fun process(id: String, hasAccess: () -> Boolean = { true }): Boolean {
        try {
            return database.withTransaction {
                val row = database.automationDao().record(id) ?: return@withTransaction false
                val capture: WalletCaptureRecord = AutomationCodec.json.decodeFromString(row.payload)
                if (!enabled || !hasAccess() || capture.session != captureSession() || capture.status == WalletCaptureStatus.IGNORED || capture.attempts >= WalletPaymentPolicy.MAX_ATTEMPTS) return@withTransaction false
                val payment: WalletPayment = capture.payment
                if (database.automationDao().record("wallet:${payment.eventId}") != null) {
                    database.automationDao().deleteRecord(id)
                    return@withTransaction false
                }
                register(payment)
                // A disable during a suspended database call rolls the transaction back.
                check(enabled && hasAccess() && capture.session == captureSession()) { "WALLET_DISABLED" }
                database.automationDao().deleteRecord(id)
                true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (failure.message == "WALLET_DISABLED") return false
            database.withTransaction {
                database.automationDao().record(id)?.let { row ->
                    val capture: WalletCaptureRecord = AutomationCodec.json.decodeFromString(row.payload)
                    putCapture(capture.copy(status = WalletCaptureStatus.FAILED,
                        attempts = (capture.attempts + 1).coerceAtMost(WalletPaymentPolicy.MAX_ATTEMPTS), error = "WALLET_STORAGE_FAILED"))
                }
            }
            throw failure
        }
    }

    private suspend fun register(payment: WalletPayment) {
        val evidence = DocumentEvidence(ScannedDocument(issuer = payment.merchant, total = payment.amount,
            currency = payment.currency, date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(payment.occurredAt))),
            fieldOrigins = setOf("issuer", "date", "currency", "total").associateWith { DocumentFieldOrigin.WALLET })
        val record = CategoryCatalog(database).assign(Invoice(documentUuid = java.util.UUID.randomUUID().toString(), proveedor = payment.merchant,
            total = payment.amount, fecha = payment.occurredAt, moneda = payment.currency, ivaPercent = null,
            paisCodigo = "", notas = context.getString(com.gastos.data.R.string.wallet_event_date), tipo = InvoiceType.GASTO, origin = "WALLET", evidence = evidence), useRules = true)
        database.invoiceDao().insertInvoice(record.toEntity())
        database.automationDao().putRecord(AutomationRecordEntity("wallet:${payment.eventId}", "PAYMENT",
            AutomationCodec.json.encodeToString(PaymentEvent(payment.eventId, "", record.documentUuid, payment.occurredAt,
                merchant = payment.merchant, amount = payment.amount, currency = payment.currency))))
        FinancialMutationStore(context, database).enqueue(record.documentUuid)
        database.chatMessageDao().insertAndTrim(createDocumentChatMessage(context, record.documentIdentity()))
    }
    suspend fun undo(id: String) = database.withTransaction {
        val row = requireNotNull(database.automationDao().record(id))
        val event: PaymentEvent = AutomationCodec.json.decodeFromString(row.payload)
        val bankLinked = database.automationDao().records("BANK_ROW").any {
            AutomationCodec.json.decodeFromString<BankTransaction>(it.payload).documentUuid == event.documentUuid
        }
        val invoice = database.invoiceDao().documentRecords().firstOrNull { it.documentUuid == event.documentUuid }
        require(WalletPaymentPolicy.canUndo(event, invoice?.toDomain(), bankLinked)) { "PAYMENT_CHANGED" }
        // A remote deletion is prepared durably by the caller before this transaction.
        val current = requireNotNull(invoice)
        database.invoiceDao().deleteByIdentity(current.id, current.documentUuid)
        database.automationDao().putRecord(row.copy(payload = AutomationCodec.json.encodeToString(event.copy(undone = true))))
    }
    private suspend fun putCapture(record: WalletCaptureRecord) = database.automationDao().putRecord(
        AutomationRecordEntity(pendingId(record.payment.eventId), WalletPaymentPolicy.CAPTURE_TYPE, AutomationCodec.json.encodeToString(record)))
    private fun pendingId(eventId: String): String = "wallet:pending:$eventId"
    companion object { const val PREFERENCES = "finai_wallet_capture" }
}
