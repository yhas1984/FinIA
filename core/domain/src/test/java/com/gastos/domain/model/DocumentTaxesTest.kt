package com.gastos.domain.model

import org.junit.Assert.*
import org.junit.Test

class DocumentTaxesTest {
    private fun tax(name: String, rate: Double, base: Double, amount: Double): DocumentTax =
        DocumentTax(name, rate, base, amount, if (rate == 0.0) TaxTreatment.ZERO_RATED else TaxTreatment.TAXABLE)

    private fun document(taxes: List<DocumentTax>, base: Double, total: Double, currency: String = "EUR"): ScannedDocument =
        ScannedDocument(kind = "ticket", issuer = "Synthetic", date = "2026-09-20", currency = currency,
            total = total, taxBase = base, taxes = taxes, taxesComplete = true, linesComplete = true,
            priceBasis = "tax_included", lines = listOf(ScannedLine("Basket", 1.0, total, total)))

    @Test fun `Spanish three rate invoice saves once without an invented global rate or line assignment`() {
        val taxes = listOf(tax("IVA", 21.0, 100.0, 21.0), tax("IVA", 10.0, 50.0, 5.0), tax("IVA", 4.0, 25.0, 1.0))
        val result = DocumentValidator.validate(document(taxes, 175.0, 202.0))
        assertEquals(emptyList<ReviewIssue>(), result.issues)
        val (invoice, products) = DocumentEvidence(result.document).toInvoice("uuid", "photo")
        assertEquals(202.0, invoice.total, 0.0)
        assertEquals(taxes, invoice.taxes)
        assertNull(invoice.ivaPercent)
        assertNull(products.single().ivaPercent)
        assertNull(products.single().ivaAmount)
        assertEquals(202.0, products.single().totalIncludingTax!!, 0.0)
        assertEquals(27.0, invoice.cuotaIva!!, 0.0)
        assertEquals(taxes, invoice.toIncome().taxes)
    }

    @Test fun `Canadian shared bases and Indian split taxes are never added twice`() {
        for ((currency, taxes) in listOf(
            "CAD" to listOf(tax("GST", 5.0, 100.0, 5.0), tax("PST", 7.0, 100.0, 7.0)),
            "INR" to listOf(tax("CGST", 6.0, 100.0, 6.0), tax("SGST", 6.0, 100.0, 6.0)))) {
            val result = DocumentValidator.validate(document(taxes, 100.0, 112.0, currency).copy(taxBase = null))
            assertEquals(emptyList<ReviewIssue>(), result.issues)
            assertEquals(100.0, result.document.taxBase!!, 0.0)
            assertNull(result.document.vatPercent)
        }
    }

    @Test fun `printed compound bases and nonstandard historical rates are retained`() {
        val taxes = listOf(tax("A", 5.0, 100.0, 5.0), tax("B", 10.0, 105.0, 10.5))
        val result = DocumentValidator.validate(document(taxes, 100.0, 115.5))
        assertTrue(result.issues.isEmpty())
        assertEquals(taxes, result.document.taxes)
        assertTrue(DocumentValidator.validate(document(listOf(tax("Historical", 17.5, 100.0, 17.5)), 100.0, 117.5, "GBP")).issues.isEmpty())
    }

    @Test fun `zero exempt outside scope and unknown survive encoding distinctly`() {
        val taxes = listOf(tax("VAT", 0.0, 25.0, 0.0), DocumentTax("VAT exempt", base = 25.0, amount = 0.0, treatment = TaxTreatment.EXEMPT),
            DocumentTax("Outside", base = 25.0, amount = 0.0, treatment = TaxTreatment.OUT_OF_SCOPE), DocumentTax("Unreadable"))
        assertEquals(taxes, DocumentTaxCodec.decode(DocumentTaxCodec.encode(taxes)))
        assertNull(taxes.last().amount)
        assertNull(DocumentTaxes.singleRate(taxes))
    }

    @Test fun `withholding is subtracted once even when also printed in legacy header`() {
        val taxes = listOf(tax("VAT", 21.0, 100.0, 21.0), tax("Retention", 15.0, 100.0, 15.0).copy(effect = TaxEffect.WITHHOLDING))
        val result = DocumentValidator.validate(document(taxes, 100.0, 106.0).copy(withholdingAmount = 15.0,
            lines = listOf(ScannedLine("Service", 1.0, 121.0, 121.0))))
        assertEquals(emptyList<ReviewIssue>(), result.issues)
        assertEquals(15.0, result.document.withholdingAmount!!, 0.0)
    }

    @Test fun `negative nonfinite inconsistent or incomplete tax cannot auto save`() {
        val valid = tax("VAT", 21.0, 100.0, 21.0)
        for (tax in listOf(valid.copy(amount = 20.0), valid.copy(rate = -1.0), valid.copy(amount = Double.NaN),
            valid.copy(treatment = TaxTreatment.EXEMPT), valid.copy(amount = null))) {
            assertFalse(DocumentValidator.validate(document(listOf(tax), 100.0, 121.0)).issues.isEmpty())
        }
        assertFalse(DocumentValidator.validate(document(listOf(valid), 100.0, 121.0).copy(taxesComplete = false)).issues.isEmpty())
    }

    @Test fun `explicit zero discount does not disable product tax consistency`() {
        val source = document(emptyList(), 100.0, 121.0).copy(discount = 0.0, vatAmount = 21.0,
            lines = listOf(ScannedLine("Wrong tax", 1.0, 121.0, 121.0, 0.0)))
        assertTrue(DocumentValidator.validate(source).issues.any { it.field == "vatAmount" })
    }

    @Test fun `net products with unknown tax retain unknown gross amount`() {
        val source = document(listOf(tax("VAT", 21.0, 100.0, 21.0), tax("VAT", 0.0, 10.0, 0.0)), 110.0, 131.0)
            .copy(priceBasis = "tax_excluded", lines = listOf(ScannedLine("Unknown assignment", 1.0, 110.0, 110.0)))
        val (invoice, products) = DocumentEvidence(source).toInvoice("uuid", "photo")
        assertEquals(131.0, invoice.total, 0.0)
        assertNull(products.single().totalIncludingTax)
    }

    @Test fun `explicit product components provide gross total without one rate`() {
        val taxes = listOf(tax("GST", 5.0, 100.0, 5.0), tax("PST", 7.0, 100.0, 7.0))
        val source = document(taxes, 100.0, 112.0, "CAD").copy(priceBasis = "tax_excluded",
            lines = listOf(ScannedLine("Item", 1.0, 100.0, 100.0, taxes = taxes)))
        val product = DocumentEvidence(source).toInvoice("uuid", "photo").second.single()
        assertEquals(taxes, product.taxes)
        assertNull(product.ivaPercent)
        assertEquals(112.0, product.totalIncludingTax!!, 0.0)
    }

    @Test fun `an unreadable tax label stays unknown without blocking reconciled amounts`() {
        val row = tax("VAT", 10.0, 100.0, 10.0).copy(name = null, treatment = TaxTreatment.UNKNOWN)
        val result = DocumentValidator.validate(document(listOf(row), 100.0, 110.0))
        assertEquals(emptyList<ReviewIssue>(), result.issues)
        assertNull(result.document.taxes.single().name)
    }

    @Test fun `exempt and taxable groups preserve their own bases and the overall subtotal`() {
        val taxes = listOf(tax("VAT", 10.0, 50.0, 5.0), DocumentTax("Exempt", base = 50.0, amount = 0.0, treatment = TaxTreatment.EXEMPT))
        val result = DocumentValidator.validate(document(taxes, 100.0, 105.0, "GBP").copy(taxBase = null))
        assertEquals(emptyList<ReviewIssue>(), result.issues)
        assertEquals(100.0, result.document.taxBase!!, 0.0)
        assertEquals(taxes, result.document.taxes)
        assertNull(result.document.vatPercent)
    }

    @Test fun `tax evidence remains nullable and deterministic after reopening`() {
        val source = DocumentEvidence(document(listOf(tax("VAT", 0.0, 100.0, 0.0)), 100.0, 100.0), originalExtraction = "printed")
        assertEquals(source, DocumentEvidenceCodec.decode(DocumentEvidenceCodec.encode(source)))
        assertEquals(DocumentValidator.validate(source), DocumentValidator.validate(source))
    }
}
