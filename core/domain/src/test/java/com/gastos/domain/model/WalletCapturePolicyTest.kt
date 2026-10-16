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
        assertFalse("otro aviso sin clave = no se fusiona por clave", WalletPaymentPolicy.isRepublishedNotice(republished.copy(notificationKey = "otra-clave"), listOf(original)))
        assertFalse("importe distinto = pago distinto", WalletPaymentPolicy.isRepublishedNotice(republished.copy(amount = 6.99), listOf(original)))
        assertFalse("registros antiguos sin clave no se fusionan", WalletPaymentPolicy.isRepublishedNotice(republished.copy(notificationKey = ""), listOf(original)))
    }

    @Test fun lateRepublicationWithFreshKeyIsStillTheSamePayment() {
        // Caso real reportado el 16-oct-2026: Wallet publica el mismo pago minutos después con clave nueva
        // (p. ej. "pago enviado" y luego "pago completado"); la ventana de 90 s no lo veía → gasto duplicado.
        val registered = PaymentEvent("event-a", "clave-a", "doc-1", 1_000, merchant = "CONSUM BENIMACLET", amount = 3.80, currency = "EUR")
        val later = WalletPayment("event-c", "CONSUM BENIMACLET", 3.80, "EUR", 1_000 + 12 * 60_000, notificationKey = "clave-b")
        assertTrue("mismo comercio+importe 12 min después = mismo pago", WalletPaymentPolicy.isDuplicateNotice(later, listOf(registered)))
        assertFalse("mismo importe en otro comercio = pago distinto", WalletPaymentPolicy.isDuplicateNotice(later.copy(merchant = "PAN PAO"), listOf(registered)))
        assertFalse("importe distinto = pago distinto", WalletPaymentPolicy.isDuplicateNotice(later.copy(amount = 3.81), listOf(registered)))
    }

    @Test fun secondRealPaymentOutsideTheContentWindowIsStillRegistered() {
        val first = PaymentEvent("event-a", "clave-a", "doc-1", 1_000, merchant = "PAN PAO", amount = 1.45, currency = "EUR")
        val sameShopLater = WalletPayment("event-c", "PAN PAO", 1.45, "EUR", 1_000 + 45 * 60_000, notificationKey = "clave-c")
        assertFalse("misma compra 45 min después = pago nuevo", WalletPaymentPolicy.isDuplicateNotice(sameShopLater, listOf(first)))
    }

    @Test fun undoneExpenseNeverBlocksTheSamePurchaseAgain() {
        // Caso: el usuario borra/deshace un gasto y vuelve a comprar lo mismo poco después → debe registrarse.
        val undone = PaymentEvent("event-a", "clave-a", "doc-1", 1_000, undone = true, merchant = "PAN PAO", amount = 1.45, currency = "EUR")
        val again = WalletPayment("event-b", "PAN PAO", 1.45, "EUR", 1_000 + 5 * 60_000, notificationKey = "clave-b")
        assertFalse("un gasto deshecho no bloquea una compra real posterior", WalletPaymentPolicy.isDuplicateNotice(again, listOf(undone)))
    }
}
