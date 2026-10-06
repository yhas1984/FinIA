package com.gastos.domain.model

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MovementPresentationTest {
    @Test fun `legacy income retains invoice source despite matching native numeric id`() {
        val legacy = Income(id = -7, documentUuid = "legacy", fecha = 1, concepto = "Payroll", monto = 100.0)
        val native = legacy.copy(id = 7, documentUuid = "native")
        assertEquals(MovementReference(MovementSource.INVOICE, "legacy"), legacy.movementReference())
        assertEquals(MovementReference(MovementSource.INCOME, "native"), native.movementReference())
    }
    @Test fun `inclusive periods include entire local day and handle daylight saving`() {
        val zone = ZoneId.of("Europe/Madrid")
        fun day(value: String) = LocalDate.parse(value).atStartOfDay(zone).toInstant().toEpochMilli()
        val start = day("2026-03-29")
        val next = day("2026-03-30")
        assertTrue(isWithinPeriod(start, start, start, zone))
        assertTrue(isWithinPeriod(next - 1, start, start, zone))
        assertFalse(isWithinPeriod(next, start, start, zone))
        assertFalse(isWithinPeriod(start - 1, start, start, zone))
        assertTrue(isWithinPeriod(next, null, null, zone))
    }
}
