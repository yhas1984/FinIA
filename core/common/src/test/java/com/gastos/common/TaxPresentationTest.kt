package com.gastos.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class TaxPresentationTest {
    private val spanish: Locale = Locale.forLanguageTag("es-ES")

    @Test fun `printed percentages are not repeated`() {
        for (rate: Double in listOf(0.0, 4.0, 10.0, 21.0)) {
            val name: String = "IVA ${rate.toInt()} %"
            assertEquals(name, formatTaxName(name, rate, "Unknown", spanish))
        }
        assertEquals("GST 7.5%", formatTaxName("GST 7.5%", 7.5, "Unknown", Locale.US))
        assertEquals("IVA 7,5\u00a0%", formatTaxName("IVA 7,5\u00a0%", 7.5, "Unknown", spanish))
    }

    @Test fun `separate rates use the active locale and preserve zero or missing information`() {
        assertEquals("IVA 7,5 %", formatTaxName("IVA", 7.5, "Unknown", spanish))
        assertEquals("VAT 7.5 %", formatTaxName("VAT", 7.5, "Unknown", Locale.US))
        assertEquals("VAT 0 %", formatTaxName("VAT", 0.0, "Unknown", Locale.US))
        assertEquals("VAT exempt", formatTaxName("VAT exempt", null, "Unknown", Locale.US))
        assertEquals("Unknown", formatTaxName(null, null, "Unknown", Locale.US))
    }

    @Test fun `different or composite printed rates never hide the stored rate`() {
        assertEquals("IVA 4 % 21 %", formatTaxName("IVA 4 %", 21.0, "Unknown", spanish))
        assertEquals("GST 5% + PST 7% 7 %", formatTaxName("GST 5% + PST 7%", 7.0, "Unknown", Locale.US))
    }
}
