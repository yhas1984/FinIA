package com.gastos.feature.chatbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FinancialQueryResolverTest {
    @Test
    fun `item takes precedence over general expenses query`() {
        val resolved = FinancialQueryResolver.resolve(
            queryType = "gastos",
            item = "banana",
            matchMode = null,
            originalQuestion = "Cuánto gasté en banana",
            productNames = listOf("Banana")
        )

        assertEquals("productos", resolved.queryType)
        assertEquals("banana", resolved.item)
        assertEquals("exact", resolved.matchMode)
    }

    @Test
    fun `known product is inferred when model omits item`() {
        val resolved = FinancialQueryResolver.resolve(
            queryType = "gastos",
            item = null,
            matchMode = null,
            originalQuestion = "Cuánto gasté en banana este mes",
            productNames = listOf("Banana", "Café molido")
        )

        assertEquals("productos", resolved.queryType)
        assertEquals("Banana", resolved.item)
        assertEquals("exact", resolved.matchMode)
    }

    @Test
    fun `group mode is kept only when explicitly requested`() {
        val resolved = FinancialQueryResolver.resolve(
            queryType = "productos",
            item = "café",
            matchMode = "group",
            originalQuestion = "Cuánto gasté en todos los cafés",
            productNames = listOf("Café", "Café con leche", "Café molido")
        )

        assertEquals("productos", resolved.queryType)
        assertEquals("group", resolved.matchMode)
    }

    @Test
    fun `provider question remains expenses when it is not a product`() {
        val resolved = FinancialQueryResolver.resolve(
            queryType = "gastos",
            item = null,
            matchMode = null,
            originalQuestion = "Cuánto gasté en Mercadona",
            productNames = listOf("Banana", "Café")
        )

        assertEquals("gastos", resolved.queryType)
        assertNull(resolved.item)
        assertNull(resolved.matchMode)
    }

    @Test
    fun `normalization ignores accents case and punctuation`() {
        assertEquals(
            "cafe con leche",
            FinancialQueryResolver.normalizeProductName("  CAFÉ-con leche! ")
        )
    }
}
