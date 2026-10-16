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
     * Ventana de contenido: un segundo aviso con el MISMO comercio, importe y moneda dentro de este margen es el
     * mismo pago (Wallet publica "pago enviado" y luego "pago completado" con clave/when nuevos y a veces minutos
     * después). Sin esta ventana el gasto se duplica: reportado por el usuario el 16-oct-2026 (Consum 3,80 ×2 y
     * Pan Pao 1,45 ×2 en un mismo día, con la 1.9.4 ya instalada).
     */
    const val CONTENT_WINDOW_MS: Long = 30 * 60_000

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

    /**
     * Guard por contenido para las re-publicaciones que se escapan de [isRepublishedNotice] (clave de aviso nueva
     * o intervalo mayor que [REPUBLICATION_WINDOW_MS]): mismo comercio + mismo importe + misma moneda dentro de
     * [CONTENT_WINDOW_MS] es el MISMO pago. El contrato se mantiene donde importa: importe o comercio distintos
     * siguen siendo pagos distintos (dos cafés iguales seguidos son dos gastos reales).
     */
    fun isDuplicateContent(payment: WalletPayment, recent: List<PaymentEvent>): Boolean =
        recent.any { event ->
            event.merchant?.equals(payment.merchant, ignoreCase = true) == true &&
                event.amount?.let { kotlin.math.abs(it - payment.amount) < 0.005 } == true &&
                event.currency?.equals(payment.currency, ignoreCase = true) == true &&
                kotlin.math.abs(event.postedAt - payment.occurredAt) <= CONTENT_WINDOW_MS
        }

    fun isDuplicateNotice(payment: WalletPayment, recent: List<PaymentEvent>): Boolean =
        isRepublishedNotice(payment, recent) || isDuplicateContent(payment, recent)
}
