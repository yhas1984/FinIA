package com.gastos.domain.model

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class WalletCapturePolicyTest {
    @Test fun historicalPaymentEventsRemainReadableWithoutInventedDetails() {
        val event: PaymentEvent = AutomationCodec.json.decodeFromString("""{"id":"old","notificationKey":"","documentUuid":"expense","postedAt":100} """)
        assertNull(event.merchant)
        assertNull(event.amount)
        assertNull(event.currency)
        assertFalse(event.undone)
    }

    @Test fun normalizedFailedCaptureSurvivesBackupAndRejectsInvalidAmounts() {
        val capture = WalletCaptureRecord(WalletPayment("synthetic", "Store", 9.5, "EUR", 100), "session",
            WalletCaptureStatus.FAILED, 1, "WALLET_STORAGE_FAILED")
        val record = AutomationRecord("wallet:pending:synthetic", WalletPaymentPolicy.CAPTURE_TYPE, AutomationCodec.json.encodeToString(capture))
        WalletPaymentPolicy.validateBackup(listOf(record))
        assertEquals(capture, AutomationCodec.json.decodeFromString<WalletCaptureRecord>(record.payload))
        assertThrows(IllegalArgumentException::class.java) { WalletPaymentPolicy.validate(capture.payment.copy(amount = Double.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { WalletPaymentPolicy.validate(capture.payment.copy(amount = -1.0)) }
    }

    @Test fun republishedNoticeSameSlotIsDuplicateOnlyInsideTheWindow() {
        val slot = "0|com.google.android.apps.walletnfcrel|7|tag|10172"
        val original = PaymentEvent("event-a", slot, "doc-1", 1_000, merchant = "Bar 21", amount = 5.99, currency = "EUR")
        val republished = WalletPayment("event-b", "Bar 21", 5.99, "EUR", 1_005, notificationKey = slot)
        assertTrue(WalletPaymentPolicy.isRepublishedNotice(republished, listOf(original)))
        assertFalse("otro aviso = otro pago (contrato existente)",
            WalletPaymentPolicy.isRepublishedNotice(republished.copy(notificationKey = "otra-clave"), listOf(original)))
        assertFalse("mismo aviso minutos después = pago nuevo",
            WalletPaymentPolicy.isRepublishedNotice(republished.copy(occurredAt = 1_000 + 5 * 60_000), listOf(original)))
        assertFalse("importe distinto = pago distinto",
            WalletPaymentPolicy.isRepublishedNotice(republished.copy(amount = 6.99), listOf(original)))
        assertFalse("registros antiguos sin clave no se fusionan",
            WalletPaymentPolicy.isRepublishedNotice(republished.copy(notificationKey = ""), listOf(original)))
    }
}
