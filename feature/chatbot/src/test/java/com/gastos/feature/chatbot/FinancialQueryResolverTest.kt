package com.gastos.feature.chatbot

import com.gastos.domain.model.Product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FinancialQueryResolverTest {
    @Test
    fun `null or invalid period defaults to month`() {
        assertEquals("mes", FinancialQueryResolver.normalizePeriod(null))
        assertEquals("mes", FinancialQueryResolver.normalizePeriod("null"))
        assertEquals("mes", FinancialQueryResolver.normalizePeriod(""))
        assertEquals("mes", FinancialQueryResolver.normalizePeriod("desconocido"))
    }

    @Test
    fun `period normalization keeps supported values`() {
        assertEquals("hoy", FinancialQueryResolver.normalizePeriod("hoy"))
        assertEquals("semana", FinancialQueryResolver.normalizePeriod("esta semana"))
        assertEquals("mes", FinancialQueryResolver.normalizePeriod("este mes"))
        assertEquals("año", FinancialQueryResolver.normalizePeriod("año"))
    }

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
    fun `category field containing a known merchant is reclassified as provider`() {
        val resolved = FinancialQueryResolver.resolve(
            queryType = "gastos",
            item = null,
            matchMode = null,
            originalQuestion = "Cuánto gasté en Mercadona",
            productNames = listOf("Banana"),
            category = "Mercadona",
            provider = null,
            providerNames = listOf("Mercadona", "Lidl"),
            categoryNames = listOf("Alimentación", "Transporte")
        )

        assertEquals("gastos", resolved.queryType)
        assertEquals("Mercadona", resolved.provider)
        assertNull(resolved.category)
    }

    @Test
    fun `known category remains separate from provider`() {
        val resolved = FinancialQueryResolver.resolve(
            queryType = "gastos",
            item = null,
            matchMode = null,
            originalQuestion = "Cuánto gasté en Alimentación",
            productNames = emptyList(),
            category = "Alimentación",
            provider = null,
            providerNames = listOf("Mercadona"),
            categoryNames = listOf("Alimentación", "Transporte")
        )

        assertEquals("gastos", resolved.queryType)
        assertEquals("Alimentación", resolved.category)
        assertNull(resolved.provider)
    }

    @Test
    fun `earned wording resolves to net balance rather than income`() {
        val resolved = FinancialQueryResolver.resolve(
            queryType = "ingresos",
            item = null,
            matchMode = null,
            originalQuestion = "Cuánto he ganado este mes",
            productNames = emptyList()
        )

        assertEquals("balance", resolved.queryType)
        assertEquals(true, FinancialQueryResolver.requestsNetBalance("Cuánto he ganado"))
    }

    @Test
    fun `normalization ignores accents case and punctuation`() {
        assertEquals(
            "cafe con leche",
            FinancialQueryResolver.normalizeProductName("  CAFÉ-con leche! ")
        )
    }

    @Test
    fun `agua matches a complete token but not aguacate`() {
        assertEquals(true, FinancialQueryResolver.isRelatedProductName("AGUA CONSUM 8L", "agua"))
        assertEquals(false, FinancialQueryResolver.isRelatedProductName("AGUACATE BANDEJA", "agua"))
    }

    @Test
    fun `generic product groups variants even when model requests exact`() {
        val resolved = FinancialQueryResolver.resolve(
            queryType = "productos",
            item = "agua",
            matchMode = "exact",
            originalQuestion = "Cuánto gasté en agua",
            productNames = listOf("AGUA CONSUM 8L", "Agua Bezoya 1,5L", "AGUACATE BANDEJA")
        )

        assertEquals("agua", resolved.item)
        assertEquals("group", resolved.matchMode)
    }

    @Test
    fun `generic product groups its only partial variant without clarification`() {
        val resolved = FinancialQueryResolver.resolve(
            queryType = "productos",
            item = "agua",
            matchMode = "exact",
            originalQuestion = "Cuánto gasté en agua",
            productNames = listOf("AGUA CONSUM 8L")
        )

        assertEquals("group", resolved.matchMode)
    }

    @Test
    fun `full unique product name remains exact`() {
        val resolved = FinancialQueryResolver.resolve(
            queryType = "productos",
            item = "AGUA CONSUM 8L",
            matchMode = "exact",
            originalQuestion = "Cuánto gasté en AGUA CONSUM 8L",
            productNames = listOf("AGUA CONSUM 8L", "Agua Bezoya 1,5L")
        )

        assertEquals("exact", resolved.matchMode)
    }

    @Test
    fun `explicit exact wording keeps clarification behavior`() {
        val resolved = FinancialQueryResolver.resolve(
            queryType = "productos",
            item = "agua",
            matchMode = "group",
            originalQuestion = "Cuánto gasté solo en agua",
            productNames = listOf("AGUA CONSUM 8L", "Agua Bezoya 1,5L")
        )

        assertEquals("exact", resolved.matchMode)
    }

    @Test
    fun `generic product can be combined with provider`() {
        val resolved = FinancialQueryResolver.resolve(
            queryType = "productos",
            item = "agua",
            matchMode = "exact",
            originalQuestion = "Cuánto gasté en agua en Consum",
            productNames = listOf("AGUA CONSUM 8L", "Agua Bezoya 1,5L"),
            provider = "Consum",
            providerNames = listOf("Consum", "Mercadona")
        )

        assertEquals("group", resolved.matchMode)
        assertEquals("Consum", resolved.provider)
    }

    @Test
    fun `group matching includes water variants and excludes aguacate`() {
        val products = listOf(
            Product(invoiceId = 1L, descripcion = "AGUA CONSUM 8L", cantidad = 1.0, precioUnitario = 8.0),
            Product(invoiceId = 2L, descripcion = "Agua Bezoya 1,5L", cantidad = 2.0, precioUnitario = 1.5),
            Product(invoiceId = 2L, descripcion = "AGUACATE BANDEJA", cantidad = 1.0, precioUnitario = 2.0)
        )

        val result = FinancialQueryResolver.matchProducts(products, "agua", "group")

        assertEquals(listOf("AGUA CONSUM 8L", "Agua Bezoya 1,5L"), result.matches.map { it.descripcion })
        assertEquals(false, result.requiresClarification)
        assertEquals(true, result.usedGroupMode)
    }

    @Test
    fun `exact name is preferred even when supersets exist`() {
        val resolved = FinancialQueryResolver.resolve(
            queryType = "productos",
            item = "AGUA CONSUM 8L",
            matchMode = null,
            originalQuestion = "Cuánto gasté en AGUA CONSUM 8L",
            productNames = listOf("AGUA CONSUM 8L", "AGUA CONSUM 8L LIMON", "AGUA CONSUM 8L NARANJA")
        )

        assertEquals("exact", resolved.matchMode)
    }

    @Test
    fun `matchProducts exact mode keeps only the exact variant`() {
        val products = listOf(
            Product(invoiceId = 1L, descripcion = "AGUA CONSUM 8L", cantidad = 1.0, precioUnitario = 8.0),
            Product(invoiceId = 2L, descripcion = "AGUA CONSUM 8L LIMON", cantidad = 1.0, precioUnitario = 9.0),
            Product(invoiceId = 3L, descripcion = "AGUA CONSUM 8L NARANJA", cantidad = 1.0, precioUnitario = 9.0)
        )

        val result = FinancialQueryResolver.matchProducts(products, "AGUA CONSUM 8L", "exact")

        assertEquals(listOf("AGUA CONSUM 8L"), result.matches.map { it.descripcion })
        assertEquals(false, result.requiresClarification)
        assertEquals(false, result.usedGroupMode)
    }

    @Test
    fun `confirmation selects the only product variant`() {
        val resolved = FinancialQueryResolver.resolveClarification(
            answer = "sí",
            requestedItem = "agua",
            variants = listOf("AGUA CONSUM 8L")
        )

        assertEquals("AGUA CONSUM 8L", resolved?.item)
        assertEquals("exact", resolved?.matchMode)
    }

    @Test
    fun `number selects one variant and all keeps group mode`() {
        val variants = listOf("Café con leche", "Café molido")

        assertEquals(
            "Café molido",
            FinancialQueryResolver.resolveClarification("2", "café", variants)?.item
        )
        assertEquals(
            "group",
            FinancialQueryResolver.resolveClarification("todas", "café", variants)?.matchMode
        )
    }
}
