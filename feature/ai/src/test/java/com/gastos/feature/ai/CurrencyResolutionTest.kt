package com.gastos.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class CurrencyResolutionTest {

    @Test
    fun `missing AI currency uses configured currency`() {
        assertEquals("MXN", resolveCurrency(null, "MXN"))
        assertEquals("ARS", resolveCurrency("", "ARS"))
    }

    @Test
    fun `explicit supported AI currency is preserved`() {
        assertEquals("USD", resolveCurrency("usd", "MXN"))
    }

    @Test
    fun `missing AI currency markers use configured currency`() {
        assertEquals("MXN", resolveCurrency("null", "MXN"))
        assertEquals("ARS", resolveCurrency("unknown", "ARS"))
    }

    @Test
    fun `explicit unsupported OCR currency is preserved for validation`() {
        assertEquals("GBP", resolveCurrency("gbp", "EUR"))
    }

    @Test
    fun `text command ignores unmentioned AI currency`() {
        assertEquals("MXN", resolveCommandCurrency("EUR", "MXN", "Gasté 100 en comida"))
    }

    @Test
    fun `text command preserves explicitly mentioned currency`() {
        assertEquals("EUR", resolveCommandCurrency("EUR", "MXN", "Gasté 100 euros"))
        assertEquals("USD", resolveCommandCurrency("USD", "MXN", "Gasté 100 USD"))
    }

    @Test
    fun `text command trusts explicit currency over mismatched AI currency`() {
        assertEquals("EUR", resolveCommandCurrency("USD", "MXN", "Gasté 100 euros"))
    }

    @Test
    fun `unsupported currency mention is preserved for validation`() {
        assertEquals("GBP", resolveCommandCurrency("GBP", "EUR", "Gasté 100 libras"))
        assertEquals("GBP", resolveCommandCurrency("EUR", "EUR", "Gasté 100 libras"))
    }
}
