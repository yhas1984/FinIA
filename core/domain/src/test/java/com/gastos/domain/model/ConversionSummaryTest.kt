package com.gastos.domain.model

import org.junit.Assert.*
import org.junit.Test

class ConversionSummaryTest {
    private fun summary(rows: List<MoneyRecord>, usdRate: Double? = null) = summarizeConversions(rows, "EUR", 42L) { value, currency, _ ->
        when (currency) { "EUR" -> value; "USD" -> usdRate?.let { value * it }; else -> null }
    }
    @Test fun `mixed currencies retain original exclusions and become complete after refresh`() {
        val rows = listOf(MoneyRecord("a", "A", 100.0, "EUR"), MoneyRecord("b", "B", 100.0, "USD"))
        val partial = summary(rows)
        assertEquals(100.0, partial.amount!!, 0.0)
        assertEquals(listOf(rows[1]), partial.excluded)
        assertEquals(mapOf("USD" to 100.0), partial.excludedByCurrency)
        assertEquals(42L, partial.updatedAt)
        val refreshed = summary(rows, 0.9)
        assertEquals(190.0, refreshed.amount!!, 0.0)
        assertFalse(refreshed.isPartial)
    }
    @Test fun `unavailable differs from empty and known zero`() {
        assertNull(summary(listOf(MoneyRecord("a", "A", 100.0, "USD"))).amount)
        assertEquals(0.0, summary(emptyList()).amount!!, 0.0)
        assertEquals(0.0, summary(listOf(MoneyRecord("a", "A", 0.0, "EUR"))).amount!!, 0.0)
    }
    @Test fun `balance keeps known side but distinguishes all missing from no movements`() {
        val missing = summary(listOf(MoneyRecord("a","A",100.0,"USD")))
        val known = summary(listOf(MoneyRecord("b","B",100.0,"EUR")))
        assertEquals(100.0,partialBalance(missing,known)!!,0.0)
        assertEquals(-100.0,partialBalance(known,missing)!!,0.0)
        assertNull(partialBalance(missing,summary(emptyList())))
        assertEquals(0.0,partialBalance(summary(emptyList()),summary(emptyList()))!!,0.0)
    }

}
