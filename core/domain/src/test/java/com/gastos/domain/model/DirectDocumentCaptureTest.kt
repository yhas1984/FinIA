package com.gastos.domain.model

import org.junit.Assert.*
import org.junit.Test

class DirectDocumentCaptureTest {
    @Test fun `incomplete mixed taxes never block or replace the printed total`() {
        val taxes: List<DocumentTax> = listOf(
            DocumentTax("IVA", 21.0, 100.0, 21.0, TaxTreatment.TAXABLE),
            DocumentTax("IVA", 10.0, 50.0, 5.0, TaxTreatment.TAXABLE),
            DocumentTax("IVA", 4.0, 25.0, 1.0, TaxTreatment.TAXABLE))
        val evidence: DocumentEvidence = DocumentEvidence(ScannedDocument(kind = "ticket", issuer = "Store", date = "2026-09-21",
            currency = "EUR", total = 203.45, taxBase = 175.0, vatAmount = 27.0, taxes = taxes, taxesComplete = false,
            linesComplete = false, lines = listOf(ScannedLine("Partial item", subtotal = 10.0))))
        val (invoice: Invoice, products: List<Product>) = evidence.toInvoice("uuid", "photo")
        assertEquals(203.45, invoice.total, 0.0)
        assertEquals(taxes, invoice.taxes)
        assertNull(invoice.ivaPercent)
        assertEquals(evidence.document.lines, invoice.evidence!!.document.lines)
        assertTrue(products.isEmpty())
    }

    @Test fun `payroll amount remains net despite incomplete or unreconciled salary rows`() {
        val payroll: PayrollDetails = PayrollDetails(periodEnd = "2026-01-31", totalDeductions = 822.09, linesComplete = false,
            lines = listOf(PayrollLine("Salary", PayrollLineType.EARNING, 1000.0),
                PayrollLine("Adjustment", PayrollLineType.EARNING, -9.90)))
        val source: DocumentEvidence = DocumentEvidence(ScannedDocument(kind = "nomina", issuer = "Employer", currency = "EUR",
            gross = 1272.71, net = 450.62, payroll = payroll, payPeriod = "January 2026"))
        val income: Income = source.toPayroll("uuid", "photo")
        assertEquals(450.62, income.monto, 0.0)
        assertEquals(1272.71, income.totalDevengado, 0.0)
        assertEquals(payroll.lines, income.evidence!!.document.payroll!!.lines)
        assertEquals(DocumentValidator.parseDate("2026-01-31"), income.fecha)
    }

    @Test fun `legacy payslip does not require the new breakdown`() {
        val source: DocumentEvidence = DocumentEvidence(ScannedDocument(kind = "nomina", issuer = "Employer", currency = "EUR",
            date = "2026-01-31", gross = 2400.0, net = 2052.0), originalExtraction = "legacy extraction")
        assertEquals(2052.0, source.toPayroll("uuid", "photo").monto, 0.0)
    }

    @Test fun `receipt without identity date and products uses capture date without inventing evidence`() {
        val source: DocumentEvidence = DocumentEvidence(ScannedDocument(kind = "ticket", currency = "USD", total = 12.50))
        val invoice: Invoice = source.toInvoice("uuid", "photo", capturedAt = 1234L).first
        assertEquals(1234L, invoice.fecha)
        assertNull(invoice.evidence!!.document.date)
        assertNull(invoice.evidence!!.document.issuer)
        assertEquals("USD", invoice.moneda)
        assertEquals(12.50, invoice.total, 0.0)
    }

    @Test fun `unreadable net never becomes gross or zero`() {
        val source: DocumentEvidence = DocumentEvidence(ScannedDocument(kind = "nomina", gross = 2400.0, total = 2400.0))
        assertThrows(UnreadableDocumentAmountException::class.java) { source.toPayroll("uuid", "photo") }
        assertThrows(UnreadableDocumentAmountException::class.java) {
            DocumentEvidence(ScannedDocument(total = Double.NaN)).toInvoice("uuid", "photo")
        }
    }

    @Test fun `discount rows and explicit zero tax remain unchanged when totals differ`() {
        val lines: List<ScannedLine> = listOf(ScannedLine("Item", 1.0, 10.0, 10.0, 0.0),
            ScannedLine("Coupon", 1.0, -2.0, -2.0, 0.0))
        val source: DocumentEvidence = DocumentEvidence(ScannedDocument(total = 7.99, currency = "EUR", vatPercent = 21.0,
            priceBasis = "tax_included", lines = lines, linesComplete = false))
        val (invoice: Invoice, products: List<Product>) = source.toInvoice("uuid", "photo")
        assertEquals(7.99, invoice.total, 0.0)
        assertEquals(2, products.size)
        assertEquals(-2.0, products.last().subtotal, 0.0)
        assertTrue(products.all { it.ivaPercent == 0.0 })
    }
}
