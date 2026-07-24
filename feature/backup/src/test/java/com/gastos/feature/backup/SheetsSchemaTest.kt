package com.gastos.feature.backup

import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.Product
import com.gastos.domain.model.Income
import com.gastos.repository.ExchangeRateProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.flow.MutableStateFlow

class SheetsSchemaTest {
    private val exchangeRateProvider = object : ExchangeRateProvider {
        override val rates = MutableStateFlow(mapOf("USD" to 1.0, "EUR" to 0.9, "VES" to 36.0))
        override val lastUpdated = MutableStateFlow(1_700_000_000_000L)
        override suspend fun refresh() = Unit
        override fun convert(amount: Double, from: String, to: String): Double? =
            convertWithMeta(amount, from, to)?.amount
    }

    private fun snapshot(target: String = "EUR") =
        SheetsSchema.ConversionSnapshot(targetCurrency = target, exchangeRateProvider = exchangeRateProvider)

    @Test
    fun `expense row matches headers and keeps id before Drive link`() {
        val row = SheetsSchema.expenseRow(
            Invoice(
                id = 9,
                fecha = 1L,
                proveedor = "Proveedor",
                tipo = InvoiceType.GASTO,
                total = 121.0,
                driveWebViewLink = "https://drive.test/9"
            ),
            snapshot()
        )

        assertEquals(SheetsSchema.recibidasHeaders.size, row.size)
        assertEquals(9L, row[13])
        assertEquals("https://drive.test/9", row[14])
        assertEquals("EUR", row[11])
    }

    @Test
    fun `product row stores invoice and product ids`() {
        val row = SheetsSchema.productRow(
            Product(
                id = 17,
                invoiceId = 9,
                descripcion = "Producto",
                precioUnitario = 10.0
            ),
            "Proveedor",
            "EUR",
            snapshot()
        )

        assertEquals(SheetsSchema.productosHeaders.size, row.size)
        assertEquals(9L, row[7])
        assertEquals(17L, row[8])
    }

    @Test
    fun `missing rate leaves converted cells blank and preserves originals`() {
        val row = SheetsSchema.expenseRow(
            Invoice(
                id = 11,
                fecha = 1L,
                proveedor = "Proveedor",
                tipo = InvoiceType.GASTO,
                moneda = "ARS",
                total = 250.0
            ),
            snapshot()
        )

        assertEquals("", row[5])
        assertEquals("", row[7])
        assertEquals("", row[10])
        assertEquals(250.0, row[15])
        assertEquals("ARS", row[16])
        assertEquals("Tasa pendiente", row[19])
    }

    @Test
    fun `income row preserves original values after conversion`() {
        val row = SheetsSchema.incomeRow(
            com.gastos.domain.model.Income(
                id = 3,
                fecha = 1L,
                concepto = "Nómina",
                monto = 400.0,
                totalDevengado = 500.0,
                totalNeto = 400.0,
                moneda = "USD"
            ),
            snapshot()
        )

        assertEquals(SheetsSchema.nominasHeaders.size, row.size)
        assertEquals("EUR", row[7])
        assertEquals(500.0, row[9])
        assertEquals(400.0, row[10])
        assertTrue(row[12] is Double)
    }

    @Test
    fun `summary includes converted incomes and expenses`() {
        val conversion = snapshot()
        val totals = SheetsSchema.summaryTotals(
            invoices = listOf(
                Invoice(
                    id = 1,
                    fecha = 1L,
                    proveedor = "Gasto VES",
                    tipo = InvoiceType.GASTO,
                    moneda = "VES",
                    total = 36_000.0
                )
            ),
            incomes = listOf(
                Income(
                    id = 1,
                    fecha = 1L,
                    concepto = "Ingreso EUR",
                    monto = 2_500.0,
                    moneda = "EUR"
                ),
                Income(
                    id = 2,
                    fecha = 1L,
                    concepto = "Ingreso VES",
                    monto = 200_000.0,
                    moneda = "VES"
                )
            ),
            conversion = conversion
        )

        assertEquals(900.0, totals.totalExpenses, 0.001)
        assertEquals(7_500.0, totals.totalIncomes, 0.001)
        assertEquals(6_600.0, totals.balance, 0.001)
        assertEquals(0, totals.pendingConversions)
        val rows = SheetsSchema.summaryRows("2026-07-24", "EUR", totals)
        assertEquals("Total Ingresos", rows[4][0])
        assertEquals(7_500.0, rows[4][1])
    }

    @Test
    fun `summary excludes missing rates and counts pending conversions`() {
        val totals = SheetsSchema.summaryTotals(
            invoices = emptyList(),
            incomes = listOf(
                Income(
                    id = 3,
                    fecha = 1L,
                    concepto = "Ingreso sin tasa",
                    monto = 500.0,
                    moneda = "ARS"
                )
            ),
            conversion = snapshot()
        )

        assertEquals(0.0, totals.totalIncomes, 0.001)
        assertEquals(1, totals.pendingConversions)
    }
}
