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
}
