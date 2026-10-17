package com.gastos.feature.chatbot

import com.gastos.domain.model.Product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class FinancialQueryResolverTest {
    @Test
    fun `null or invalid period defaults to month`() {
        assertEquals("mes", FinancialQueryResolver.normalizePeriod(null))
        assertEquals("mes", FinancialQueryResolver.normalizePeriod("null"))
        assertEquals("mes", FinancialQueryResolver.normalizePeriod(""))
        assertEquals("mes", FinancialQueryResolver.normalizePeriod("desconocido"))
    }

    @Test
    fun `english period aliases normalize correctly`() {
        assertEquals("semana", FinancialQueryResolver.normalizePeriod("this week"))
        assertEquals("año", FinancialQueryResolver.normalizePeriod("year"))
    }

    @Test
    fun `period normalization keeps supported values`() {
        assertEquals("hoy", FinancialQueryResolver.normalizePeriod("hoy"))
        assertEquals("semana", FinancialQueryResolver.normalizePeriod("esta semana"))
        assertEquals("mes", FinancialQueryResolver.normalizePeriod("este mes"))
        assertEquals("año", FinancialQueryResolver.normalizePeriod("año"))
    }

    private val zona = TimeZone.getTimeZone("Europe/Madrid")

    private fun momento(anio: Int, mes: Int, dia: Int): Long =
        Calendar.getInstance(zona).apply {
            clear()
            set(anio, mes - 1, dia, 12, 0, 0)
        }.timeInMillis

    private fun limite(anio: Int, mes: Int, dia: Int, hora: Int, minuto: Int, segundo: Int, ms: Int): Long =
        Calendar.getInstance(zona).apply {
            clear()
            set(anio, mes - 1, dia, hora, minuto, segundo)
            set(Calendar.MILLISECOND, ms)
        }.timeInMillis

    @Test
    fun `a named month is kept instead of falling back to the current month`() {
        val hoy = momento(2026, 10, 17)
        assertEquals("mes:2026-08", FinancialQueryResolver.normalizePeriod("agosto", hoy, zona))
        assertEquals("mes:2026-08", FinancialQueryResolver.normalizePeriod("en el mes de agosto", hoy, zona))
        assertEquals("mes:2026-08", FinancialQueryResolver.normalizePeriod("agosto de 2026", hoy, zona))
        assertEquals("mes:2025-08", FinancialQueryResolver.normalizePeriod("agosto de 2025", hoy, zona))
        assertEquals("mes:2026-09", FinancialQueryResolver.normalizePeriod("septiembre", hoy, zona))
        assertEquals("mes:2026-08", FinancialQueryResolver.normalizePeriod("2026-08", hoy, zona))
        assertEquals("mes:2026-08", FinancialQueryResolver.normalizePeriod("08/2026", hoy, zona))
    }

    @Test
    fun `month names resolve to the most recent past year`() {
        val enero = momento(2026, 1, 10)
        assertEquals("mes:2025-12", FinancialQueryResolver.normalizePeriod("diciembre", enero, zona))
        assertEquals("mes:2026-01", FinancialQueryResolver.normalizePeriod("enero", enero, zona))
    }

    @Test
    fun `relative months and explicit ranges`() {
        val hoy = momento(2026, 10, 17)
        assertEquals("mes:2026-09", FinancialQueryResolver.normalizePeriod("el mes pasado", hoy, zona))
        assertEquals("mes:2026-09", FinancialQueryResolver.normalizePeriod("mes anterior", hoy, zona))
        assertEquals("mes:2026-11", FinancialQueryResolver.normalizePeriod("el mes que viene", hoy, zona))
        assertEquals(
            "rango:2026-08-01:2026-08-15",
            FinancialQueryResolver.normalizePeriod("2026-08-01..2026-08-15", hoy, zona)
        )
    }

    @Test
    fun `the period named in the question wins over the model`() {
        val hoy = momento(2026, 10, 17)
        assertEquals(
            "mes:2026-08",
            FinancialQueryResolver.periodFromQuestion("cuando gaste en Mercadona en el mes de agosto?", hoy, zona)
        )
        assertNull(FinancialQueryResolver.periodFromQuestion("y este mes?", hoy, zona))
        assertNull(FinancialQueryResolver.periodFromQuestion("cuanto he gastado", hoy, zona))
    }

    @Test
    fun `period range covers the whole requested month`() {
        val hoy = momento(2026, 10, 17)
        val (inicio, fin) = FinancialQueryResolver.periodRange("mes:2026-08", hoy, zona)
        assertEquals(limite(2026, 8, 1, 0, 0, 0, 0), inicio)
        assertEquals(limite(2026, 8, 31, 23, 59, 59, 999), fin)
        assertEquals(31L * 24 * 60 * 60 * 1000, fin - inicio + 1)
    }

    @Test
    fun `period range for explicit dates`() {
        val hoy = momento(2026, 10, 17)
        val (inicio, fin) = FinancialQueryResolver.periodRange("rango:2026-08-01:2026-08-15", hoy, zona)
        assertEquals(limite(2026, 8, 1, 0, 0, 0, 0), inicio)
        assertEquals(limite(2026, 8, 15, 23, 59, 59, 999), fin)
    }

    @Test
    fun `period label names the specific month`() {
        assertEquals("agosto de 2026", FinancialQueryResolver.periodLabel("mes:2026-08", "es"))
        assertEquals("August 2026", FinancialQueryResolver.periodLabel("mes:2026-08", "en"))
        assertEquals("este mes", FinancialQueryResolver.periodLabel("mes", "es"))
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
    fun `english query terms map to balance and income`() {
        assertEquals(true, FinancialQueryResolver.requestsNetBalance("what's left this month"))
        val resolved = FinancialQueryResolver.resolve(
            queryType = "income",
            item = null,
            matchMode = null,
            originalQuestion = "how much income did I receive",
            productNames = emptyList()
        )
        assertEquals("ingresos", resolved.queryType)
    }

    @Test
    fun `english query contract normalizes period and query type`() {
        assertEquals("mes", FinancialQueryResolver.normalizePeriod("this month"))
        val resolved = FinancialQueryResolver.resolve(
            queryType = "products_by_store",
            item = null,
            matchMode = null,
            originalQuestion = "what did I buy at Consum",
            productNames = emptyList(),
            provider = "Consum"
        )
        assertEquals("productos_por_comercio", resolved.queryType)
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
    fun `subcategory is preserved independently from category`() {
        val resolved = FinancialQueryResolver.resolve(
            queryType = "gastos",
            item = null,
            matchMode = null,
            originalQuestion = "Cuánto he gastado en la subcategoría Cafetería este mes",
            productNames = emptyList(),
            category = "Alimentación",
            subcategory = "Cafetería"
        )

        assertEquals("gastos", resolved.queryType)
        assertEquals("Alimentación", resolved.category)
        assertEquals("Cafetería", resolved.subcategory)
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
    fun `solo modifier applies to provider not product`() {
        val resolved = FinancialQueryResolver.resolve(
            queryType = "productos",
            item = "agua",
            matchMode = "auto",
            originalQuestion = "Cuánto gasté solo en Consum en agua",
            productNames = listOf("AGUA CONSUM 8L", "Agua Bezoya 1,5L"),
            provider = "Consum",
            providerNames = listOf("Consum", "Mercadona")
        )

        assertEquals("group", resolved.matchMode)
        assertEquals("Consum", resolved.provider)
    }

    @Test
    fun `solo without provider keeps exact intent on the product`() {
        val resolved = FinancialQueryResolver.resolve(
            queryType = "productos",
            item = "agua",
            matchMode = "auto",
            originalQuestion = "Cuánto gasté solo en agua",
            productNames = listOf("AGUA CONSUM 8L", "Agua Bezoya 1,5L")
        )

        assertEquals("exact", resolved.matchMode)
    }

    @Test
    fun `list-by-commerce question routes to product listing with provider`() {
        val resolved = FinancialQueryResolver.resolve(
            queryType = "gastos",
            item = null,
            matchMode = null,
            originalQuestion = "Qué he comprado en Consum",
            productNames = emptyList(),
            provider = "Consum",
            providerNames = listOf("Consum", "Mercadona")
        )

        assertEquals("productos_por_comercio", resolved.queryType)
        assertEquals("Consum", resolved.provider)
    }

    @Test
    fun `list-by-commerce question without provider stays global`() {
        val resolved = FinancialQueryResolver.resolve(
            queryType = "gastos",
            item = null,
            matchMode = null,
            originalQuestion = "Qué productos he comprado",
            productNames = emptyList()
        )

        assertEquals("productos_por_comercio", resolved.queryType)
    }

    @Test
    fun `provider purchase verb routes to product list without product keyword`() {
        val resolved = FinancialQueryResolver.resolve(
            queryType = "gastos",
            item = null,
            matchMode = null,
            originalQuestion = "Qué he comprado en Consum",
            productNames = emptyList(),
            provider = "Consum",
            providerNames = listOf("Consum", "Mercadona")
        )

        assertEquals("productos_por_comercio", resolved.queryType)
        assertEquals("Consum", resolved.provider)
    }

    @Test
    fun `list-by-commerce wins over gastos when no product is asked`() {
        val resolved = FinancialQueryResolver.resolve(
            queryType = "gastos",
            item = null,
            matchMode = null,
            originalQuestion = "Qué he comprado en Consum",
            productNames = emptyList(),
            provider = "Consum",
            providerNames = listOf("Consum", "Mercadona")
        )

        assertEquals("productos_por_comercio", resolved.queryType)
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
