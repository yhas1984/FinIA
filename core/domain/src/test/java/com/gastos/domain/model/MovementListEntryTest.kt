package com.gastos.domain.model

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MovementListEntryTest {
    private fun day(value: String): Long = LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun row(id: Long): MovementListEntry = MovementListEntry(id, "uuid-$id", MovementSource.INVOICE, false,
        "Café Estación", day("2026-10-05"), 2.5, "EUR", "Alimentación", "Restaurantes", "FAC-$id", "Viaje de trabajo")

    @Test fun `search matches accents document numbers and notes without changing values`() {
        val record = row(19)
        assertTrue(record.matchesSearch("  CAFE   estacion "))
        assertTrue(record.matchesSearch("FAC-19 trabajo"))
        assertFalse(record.matchesSearch("FAC-190"))
        assertEquals("Café Estación", record.description)
    }

    @Test fun `same filter drives count pages and financial records for ten thousand rows`() {
        val rows = (1L..10_000L).map { row(it).copy(currency = if (it % 2L == 0L) "USD" else "EUR") }
        val filter = MovementListFilter(query = "trabajo", category = "alimentacion", start = day("2026-10-01"), end = day("2026-10-31"))
        val results = rows.filter(filter::includes)
        val page = results.take(MovementListFilter.PAGE_SIZE)
        val total = summarizeConversions(results.map { it.moneyRecord() }, "EUR", null) { amount, from, _ -> amount.takeIf { from == "EUR" } }
        assertEquals(50, page.size)
        assertEquals(10_000, total.recordCount)
        assertEquals(5_000, total.excluded.size)
        assertEquals(12_500.0, total.amount!!, 0.001)
        assertTrue(rows.none { filter.copy(start = day("2026-11-01")).includes(it) })
    }

    @Test fun `income list identity preserves legacy storage origin`() {
        val legacy = Invoice(id = 41, documentUuid = "legacy", fecha = 1, proveedor = "Employer", total = 900.0, tipo = InvoiceType.INGRESO).asLegacyIncome().listEntry()
        assertEquals(-41L, legacy.id)
        assertEquals(MovementReference(MovementSource.INVOICE, "legacy"), legacy.movementReference())
        assertEquals("INCOME:legacy", legacy.moneyRecord().id)
    }
}
