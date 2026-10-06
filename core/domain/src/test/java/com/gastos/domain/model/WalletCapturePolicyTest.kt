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
}
