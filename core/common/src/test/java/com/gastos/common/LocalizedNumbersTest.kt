package com.gastos.common

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class LocalizedNumbersTest {
    private val spanish = Locale.forLanguageTag("es-ES")
    @Test fun `decimal and grouping follow the active locale`() {
        assertEquals(1234.56, LocalizedNumbers.parse("1.234,56", spanish)!!, 0.001)
        assertEquals(1234.56, LocalizedNumbers.parse("1,234.56", Locale.US)!!, 0.001)
        assertEquals(1234.0, LocalizedNumbers.parse("1.234", spanish)!!, 0.001)
        assertEquals(1234.0, LocalizedNumbers.parse("1,234", Locale.US)!!, 0.001)
        assertEquals(12.5, LocalizedNumbers.parse("12.50", spanish)!!, 0.001)
        assertEquals(12.5, LocalizedNumbers.parse("12,50", Locale.US)!!, 0.001)
    }
    @Test fun `invalid text grouping and nonfinite numbers are rejected`() {
        listOf("12 euros", "1.23.456", "1,234,56", "12.34,56", "NaN", "Infinity", "--1", "-+1", "", "1e3")
            .forEach { assertNull(it, LocalizedNumbers.parse(it, spanish)) }
    }
    @Test fun `formatted values round trip without grouping ambiguity`() {
        listOf(0.0, 0.001, 1234.56, -100.3, 21.0).forEach { amount ->
            listOf(spanish, Locale.US).forEach { locale ->
                assertEquals(amount, LocalizedNumbers.parse(LocalizedNumbers.format(amount, locale), locale)!!, 0.000001)
            }
        }
    }
}
