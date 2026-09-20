package com.gastos.common

import com.gastos.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class TaxFormTest {
    private val locale: Locale = Locale.forLanguageTag("es-ES")
    @Test fun `localized manual percentages recalculate without replacing explicit zero`() {
        val row = TaxFormRow(name = "IVA", rate = "4", base = "12,50")
        assertEquals(0.5, row.parse(locale, "EUR")!!.amount!!, 0.0)
        assertEquals(0.0, row.copy(rate = "0").parse(locale, "EUR")!!.amount!!, 0.0)
        assertNull(row.copy(rate = "unknown").parse(locale, "EUR"))
        assertNull(row.copy(rate = "", base = "").parse(locale, "EUR")!!.amount)
    }

    @Test fun `shared bases preserve the net base and retention is not counted twice`() {
        val taxes = listOf(DocumentTax("GST", 5.0, 100.0, 5.0, TaxTreatment.TAXABLE),
            DocumentTax("PST", 7.0, 100.0, 7.0, TaxTreatment.TAXABLE),
            DocumentTax("Retention", 2.0, 100.0, 2.0, TaxTreatment.TAXABLE, TaxEffect.WITHHOLDING))
        val result = reconcileTaxForm(taxes, 110.0, null, 2.0, "CAD")!!
        assertEquals(100.0, result.base, 0.0)
        assertEquals(2.0, result.withholding, 0.0)
        assertNull(reconcileTaxForm(taxes, 130.0, 100.0, 2.0, "CAD"))
    }

    @Test fun `editing unrelated fields roundtrips tax meaning and precision`() {
        val tax = DocumentTax("VAT exempt", base = 1234.56, amount = 0.0, treatment = TaxTreatment.EXEMPT)
        assertEquals(tax, TaxFormRow.from(tax, locale).parse(locale, "EUR"))
        assertEquals("1234,56", TaxFormRow.from(tax, locale).base)
    }
}
