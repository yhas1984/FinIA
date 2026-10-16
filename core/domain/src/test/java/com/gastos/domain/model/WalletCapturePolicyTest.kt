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
        val slot = "0|com.google.android.apps.walletnfcrel|1001|96041512|10378"
        val original = PaymentEvent("event-a", slot, "doc-1", 1_000, merchant = "Bar 21", amount = 5.99, currency = "EUR")
        val republished = WalletPayment("event-b", "Bar 21", 5.99, "EUR", 1_005, notificationKey = slot)
        assertTrue("mismo aviso = mismo pago", WalletPaymentPolicy.isRepublishedNotice(republished, listOf(original)))
        // Otro aviso de la MISMA app pero con otro pago (otro tag) = otro pago: aquí caen dos cafés iguales seguidos.
        val otherPayment = "0|com.google.android.apps.walletnfcrel|1001|96041999|10378"
        assertFalse("otro aviso (otro tag) = pago distinto aunque el comercio y el importe coincidan",
            WalletPaymentPolicy.isRepublishedNotice(republished.copy(notificationKey = otherPayment), listOf(original)))
        assertFalse("sin identidad de aviso no se fusiona nunca",
            WalletPaymentPolicy.isRepublishedNotice(republished.copy(notificationKey = ""), listOf(original)))
    }

    @Test fun sameMerchantAndAmountInDifferentPaymentsAreBothRegistered() {
        // El caso que rompía la versión por contenido: dos compras reales iguales (mismo bar, mismo importe) minutos
        // después. Los avisos son distintos (tag distinto) → deben entrar las dos.
        val first = PaymentEvent("event-a", "0|com.google.android.apps.walletnfcrel|1001|111111|10378", "doc-1",
            1_000, merchant = "PAN PAO", amount = 1.45, currency = "EUR")
        val second = WalletPayment("event-b", "PAN PAO", 1.45, "EUR", 1_000 + 10 * 60_000,
            notificationKey = "0|com.google.android.apps.walletnfcrel|1001|222222|10378")
        assertFalse("dos pagos reales iguales se registran los dos", WalletPaymentPolicy.isDuplicateNotice(second, listOf(first)))
    }

    @Test fun lateRepublicationOfTheSameNoticeIsMergedWithoutAnyTimeWindow() {
        // Wallet actualiza el mismo aviso (mismo tag) al confirmar el pago, y puede tardar minutos u horas.
        val registered = PaymentEvent("event-a", "0|com.google.android.apps.walletnfcrel|1001|96041512|10378", "doc-1",
            1_000, merchant = "CONSUM BENIMACLET", amount = 3.80, currency = "EUR")
        val later = WalletPayment("event-c", "CONSUM BENIMACLET", 3.80, "EUR", 1_000 + 6 * 60 * 60_000,
            notificationKey = "0|com.google.android.apps.walletnfcrel|1001|96041512|10378")
        assertTrue("mismo aviso 6 h después = mismo pago", WalletPaymentPolicy.isDuplicateNotice(later, listOf(registered)))
        // El mismo aviso manda sobre el importe: si Wallet corrige el importe al confirmar, es el MISMO pago.
        assertTrue("el mismo aviso manda aunque Wallet ajuste el importe",
            WalletPaymentPolicy.isDuplicateNotice(later.copy(amount = 3.81), listOf(registered)))
        // Y un aviso distinto es un pago distinto, aunque el comercio y el importe coincidan.
        assertFalse("otro aviso = otro pago",
            WalletPaymentPolicy.isDuplicateNotice(later.copy(notificationKey = "0|com.google.android.apps.walletnfcrel|1001|96041999|10378"), listOf(registered)))
    }

    @Test fun undoneExpenseNeverBlocksTheSameNoticeAgain() {
        // Si el usuario deshace el gasto, el evento deja de bloquear: volver a registrar el mismo aviso es válido.
        val undone = PaymentEvent("event-a", "0|com.google.android.apps.walletnfcrel|1001|96041512|10378", "doc-1",
            1_000, undone = true, merchant = "PAN PAO", amount = 1.45, currency = "EUR")
        val again = WalletPayment("event-b", "PAN PAO", 1.45, "EUR", 1_000 + 5 * 60_000,
            notificationKey = "0|com.google.android.apps.walletnfcrel|1001|96041512|10378")
        assertFalse("un gasto deshecho no bloquea el mismo aviso", WalletPaymentPolicy.isDuplicateNotice(again, listOf(undone)))
    }
}
