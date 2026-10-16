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
     * Google Wallet publica el MISMO pago en varios avisos (preparado/enviado y luego completado) reutilizando
     * la MISMA notificación (mismos id y tag → misma pasta del aviso) y suele cambiar el `when` con cada
     * actualización. La identidad del pago es ese hash del aviso, no el comercio/importe: dos cafés iguales en el
     * mismo bar son dos pagos reales y deben registrarse los dos.
     *
     * @return la pista del aviso (`id|tag`) o cadena vacía si el aviso no la trae.
     */
    fun noticeIdentity(notificationKey: String): String {
        val parts = notificationKey.split('|')
        return if (parts.size >= 4) "${parts[2]}|${parts[3]}" else ""
    }

    /**
     * Mismo aviso = mismo pago, aunque Wallet le haya cambiado el `when` (el eventId, que es hash de pkg|key|when,
     * cambiaría y por eso la deduplicación por eventId sola no lo detecta). Sin ventana de tiempo y sin comparar
     * importe ni comercio: si el aviso es el mismo, el pago es el mismo.
     */
    fun isRepublishedNotice(payment: WalletPayment, recent: List<PaymentEvent>): Boolean {
        val identity = noticeIdentity(payment.notificationKey)
        if (identity.isEmpty()) return false
        return recent.any { event -> !event.undone && noticeIdentity(event.notificationKey) == identity }
    }

    fun isDuplicateNotice(payment: WalletPayment, recent: List<PaymentEvent>): Boolean =
        isRepublishedNotice(payment, recent)
}
