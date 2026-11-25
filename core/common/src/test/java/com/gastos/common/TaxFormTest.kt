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

    @Test fun `a non taxable treatment zeroes rate and amount and keeps the breakdown calculable`() {
        val row = TaxFormRow(name = "IVA", rate = "21", base = "100", amount = "21")
        // el caso medido en el binario de Play: la fila conservaba el 21 % y los 21 € al pasar a no sujeta
        assertNull(TaxFormRow.parseAll(listOf(row.copy(treatment = TaxTreatment.OUT_OF_SCOPE)), locale, "EUR"))
        val exempt = row.withTreatment(TaxTreatment.EXEMPT, locale)
        assertEquals("0", exempt.rate)
        assertEquals("0", exempt.amount)
        assertEquals(100.0, exempt.parse(locale, "EUR")!!.base!!, 0.0)
        val parsed = TaxFormRow.parseAll(listOf(exempt), locale, "EUR")!!
        assertTrue(DocumentTaxes.validate(parsed, "EUR").isEmpty())
        assertEquals(0.0, DocumentTaxes.total(parsed, TaxEffect.CHARGE)!!, 0.0)
        // volver a gravado no inventa un 21 % que el usuario no ha escrito
        assertEquals(0.0, exempt.withTreatment(TaxTreatment.TAXABLE, locale).parse(locale, "EUR")!!.rate!!, 0.0)
    }

    @Test fun `editing unrelated fields roundtrips tax meaning and precision`() {
        val tax = DocumentTax("VAT exempt", base = 1234.56, amount = 0.0, treatment = TaxTreatment.EXEMPT)
        assertEquals(tax, TaxFormRow.from(tax, locale).parse(locale, "EUR"))
        assertEquals("1234,56", TaxFormRow.from(tax, locale).base)
    }
}
