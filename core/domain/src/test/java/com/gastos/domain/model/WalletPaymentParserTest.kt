package com.gastos.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WalletPaymentParserTest {
    @Test
    fun recognizesMerchantTitleAndSpanishMaskedCardPayment() {
        val payment: WalletPayment = requireNotNull(parse("Google Play Apps", "5,99 € con Mastercard Open Debit ••0000"))
        assertEquals("Google Play Apps", payment.merchant)
        assertEquals(5.99, payment.amount, 0.0)
        assertEquals("EUR", payment.currency)
        assertEquals(EVENT_TIME, payment.occurredAt)
    }

    @Test
    fun supportsNotificationSpacingAndEnglishCurrencyPlacement() {
        val spanish: WalletPayment = requireNotNull(parse("  Test Market  ", "1.234,56\u00a0€ con Visa\u202f••0000"))
        assertEquals("Test Market", spanish.merchant)
        assertEquals(1234.56, spanish.amount, 0.0)
        val english: WalletPayment = requireNotNull(parse("Test Market", "£5.99 with Mastercard Debit •• 0000"))
        assertEquals("GBP", english.currency)
        assertEquals(5.99, english.amount, 0.0)
        assertEquals("USD", requireNotNull(parse("Test Market", "5.99 USD with Visa **0000")).currency)
    }

    @Test
    fun preservesAffirmativePaymentFormat() {
        val payment: WalletPayment = requireNotNull(parse("Google Wallet", "Has pagado 5,99 EUR en Test Market."))
        assertEquals("Test Market", payment.merchant)
        assertEquals(5.99, payment.amount, 0.0)
    }

    @Test
    fun numericMerchantNameIsNotInterpretedAsAnotherAmount() {
        val payment: WalletPayment = requireNotNull(parse("Bar 21", "5,99 € con Visa ••0000"))
        assertEquals("Bar 21", payment.merchant)
        assertEquals(5.99, payment.amount, 0.0)
    }

    @Test
    fun bankAndWalletNoticesForSamePaymentProduceOnlyWalletCandidate() {
        val candidates: List<WalletPayment> = listOfNotNull(
            parse("Google Play Apps", "5,99 € con Mastercard Open Debit ••0000"),
            WalletPaymentParser.parse("com.example.bank", "bank-event", EVENT_TIME, "Test Bank",
                "Pago con tu tarjeta **0000 el 8/10 11:17 por 5,99 EUR en Google Play Apps Dublin IE.")
        )
        assertEquals(1, candidates.size)
        assertEquals("Google Play Apps", candidates.single().merchant)
    }

    @Test
    fun notificationRetryKeepsIdentityButSeparateEqualPaymentsRemainPossible() {
        val first: WalletPayment = requireNotNull(parse("Test Market", "5,99 € con Visa ••0000"))
        val repeated: WalletPayment = requireNotNull(parse("Test Market", "5,99 € con Visa ••0000"))
        val another: WalletPayment = requireNotNull(WalletPaymentParser.parse(WalletPaymentParser.PACKAGE,
            "another-event", EVENT_TIME + 1, "Test Market", "5,99 € con Visa ••0000"))
        assertEquals(first.eventId, repeated.eventId)
        assertNotEquals(first.eventId, another.eventId)
    }

    @Test
    fun rejectsIncompleteOrAmbiguousCompactPayments() {
        val bodies: List<String> = listOf(
            "5,99 €", "5,99 € con Visa", "5,99 € con Visa 0000", "5,99 € con ••0000",
            "5,99 $ con Visa ••0000", "5,99 XYZ con Visa ••0000", "0,00 € con Visa ••0000",
            "-5,99 € con Visa ••0000", "−5,99 € con Visa ••0000", "1,234 € con Visa ••0000",
            "5,99 € con Visa ••0000; saldo 100 EUR", "5,99 € con Visa ••0000 y otra compra",
            "Oferta: 5,99 € con Visa ••0000"
        )
        bodies.forEach { body: String -> assertNull(body, parse("Test Market", body)) }
    }

    @Test
    fun rejectsNonPaymentStatusesEvenWithAmountAndCard() {
        val statuses: List<String> = listOf("Pago pendiente", "Pago rechazado", "Pago cancelado", "Devolución",
            "Payment failed", "Refund", "Verification", "Código de seguridad", "OTP")
        statuses.forEach { title: String -> assertNull(title, parse(title, "5,99 € con Visa ••0000")) }
        assertNull(parse("Test Market", "5,99 € con Visa ••0000 pendiente"))
    }

    @Test
    fun requiresMerchantAndGenuineWalletSource() {
        listOf("", "Google Wallet", "Google Pay", "Wallet", "Pago", "Payment").forEach { title: String ->
            assertNull(title, parse(title, "5,99 € con Visa ••0000"))
        }
        assertNull(WalletPaymentParser.parse("com.example.bank", "event", EVENT_TIME,
            "Test Market", "5,99 € con Visa ••0000"))
        assertNotNull(parse("Google Play Apps", "5,99 € con Visa ••0000"))
    }

    private fun parse(title: String, body: String): WalletPayment? =
        WalletPaymentParser.parse(WalletPaymentParser.PACKAGE, "wallet-event", EVENT_TIME, title, body)

    private companion object {
        const val EVENT_TIME: Long = 1_791_449_820_000L
    }
}
