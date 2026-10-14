package com.gastos.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class WalletCaptureStatus { PENDING, FAILED, IGNORED }

/** Contains normalized payment fields only, never the original notification. */
@Serializable
data class WalletCaptureRecord(
    val payment: WalletPayment,
    val session: String,
    val status: WalletCaptureStatus = WalletCaptureStatus.PENDING,
    val attempts: Int = 0,
    val error: String? = null
)

object WalletPaymentPolicy {
    const val MAX_ATTEMPTS: Int = 5
    const val CAPTURE_TYPE: String = "WALLET_CAPTURE"
    fun canUndo(event: PaymentEvent, invoice: Invoice?, bankLinked: Boolean): Boolean =
        !event.undone && !bankLinked && invoice != null && invoice.documentUuid == event.documentUuid &&
            invoice.origin == "WALLET" && invoice.financialRevision == 0L && invoice.imagenUri == null && invoice.driveFileId == null

    fun validate(payment: WalletPayment) {
        require(payment.eventId.isNotBlank() && payment.eventId.length <= 100 && payment.merchant.length in 2..100)
        require(payment.amount.isFinite() && payment.amount > 0 && payment.occurredAt > 0)
        require(runCatching { java.util.Currency.getInstance(payment.currency) }.isSuccess)
    }

    fun validateBackup(records: List<AutomationRecord>) {
        records.filter { it.type == CAPTURE_TYPE }.forEach { record ->
            val capture: WalletCaptureRecord = AutomationCodec.json.decodeFromString(record.payload)
            validate(capture.payment)
            require(record.id == "wallet:pending:${capture.payment.eventId}" && capture.session.length in 1..100)
            require(capture.attempts in 0..MAX_ATTEMPTS && capture.error in setOf(null, "WALLET_STORAGE_FAILED"))
        }
    }

    /** Ventana en la que una re-publicación del MISMO aviso cuenta como el mismo pago, no como uno nuevo. */
    const val REPUBLICATION_WINDOW_MS: Long = 90_000

    /**
     * Google Wallet puede re-publicar/actualizar la notificación del mismo pago (p. ej. al confirmar el estado
     * final) con un `when` nuevo → el eventId (hash de pkg|key|when) cambia y la deduplicación por eventId no lo
     * detecta. Este guard: misma clave de aviso + mismo comercio/importe/moneda + diferencia de tiempo dentro de
     * la ventana ⇒ es el MISMO pago y no debe crear un segundo gasto.
     */
    fun isRepublishedNotice(payment: WalletPayment, recent: List<PaymentEvent>): Boolean =
        payment.notificationKey.isNotBlank() && recent.any { event ->
            event.notificationKey == payment.notificationKey &&
                event.merchant?.equals(payment.merchant, ignoreCase = true) == true &&
                event.amount?.let { kotlin.math.abs(it - payment.amount) < 0.005 } == true &&
                event.currency?.equals(payment.currency, ignoreCase = true) == true &&
                kotlin.math.abs(event.postedAt - payment.occurredAt) <= REPUBLICATION_WINDOW_MS
        }
}
