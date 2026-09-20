package com.gastos.domain.model

import org.junit.Assert.*
import org.junit.Test

class DocumentCaptureTest {
    @Test fun `printed totals prove the tax basis without a manual question or changing prices`() {
        val included = DocumentValidator.validate(valid().copy(priceBasis = null))
        assertTrue(included.issues.isEmpty())
        assertEquals("tax_included", included.document.priceBasis)
        assertTrue("priceBasis" in included.derivedFields)
        assertEquals(valid().lines, included.document.lines)
        val excluded = DocumentValidator.validate(valid().copy(priceBasis = null,
            lines = listOf(ScannedLine("Service", 1.0, 100.0, 100.0, 21.0))))
        assertTrue(excluded.issues.isEmpty())
        assertEquals("tax_excluded", excluded.document.priceBasis)
    }

    @Test fun `unproven tax basis and inconsistent explicit basis still require review`() {
        for (source in listOf(valid().copy(priceBasis = null, taxBase = null),
            valid().copy(priceBasis = null, total = 500.0), valid().copy(priceBasis = "tax_excluded"))) {
            assertTrue(DocumentValidator.validate(source).issues.isNotEmpty())
        }
        assertEquals("tax_excluded", DocumentValidator.validate(valid().copy(priceBasis = "tax_excluded")).document.priceBasis)
    }
    private fun valid(): ScannedDocument = ScannedDocument(kind = "factura_recibida", date = "2026-09-19", issuer = "Synthetic Store",
        issuerTaxId = "ES-B123", number = "F-0001", currency = "EUR", country = "ES", total = 121.0,
        taxBase = 100.0, vatAmount = 21.0, vatPercent = 21.0, priceBasis = "tax_included", linesComplete = true,
        lines = listOf(ScannedLine("Synthetic item", 1.0, 121.0, 121.0, 21.0)))

    @Test fun `valid evidence preserves invoice identity products and zero tax lines`() {
        val source = valid().copy(total = 131.0, taxBase = 110.0,
            lines = valid().lines + ScannedLine("Zero rate", 1.0, 10.0, 10.0, 0.0))
        val (invoice, products) = DocumentEvidence(source).toInvoice("uuid", "content://image")
        assertEquals("F-0001", invoice.numeroFactura)
        assertEquals(0.0, products.last().ivaPercent!!, 0.0)
        assertEquals(2, invoice.evidence!!.document.lines.size)
    }

    @Test fun `missing dates and impossible dates never become today`() {
        listOf(null, "", "2026-02-30", "2026-9-19", "2026-09-19extra").forEach {
            assertNull(DocumentValidator.parseDate(it))
            assertTrue(DocumentValidator.validate(valid().copy(date = it)).issues.any { issue -> issue.field == "date" })
        }
    }

    @Test fun `gross payroll never substitutes net`() {
        val payroll = valid().copy(kind = "nomina", gross = 3000.0, net = null, total = 3000.0, lines = emptyList())
        assertTrue(DocumentValidator.validate(payroll).issues.any { it.field == "net" })
        assertThrows(IllegalArgumentException::class.java) { DocumentEvidence(payroll).toPayroll("uuid", "photo") }
    }

    @Test fun `missing gross stays absent in payroll evidence`() {
        val payroll = valid().copy(kind = "nomina", gross = null, net = 2000.0, lines = emptyList())
        val income = DocumentEvidence(payroll).toPayroll("uuid", "photo")
        assertEquals(2000.0, income.monto, 0.0)
        assertEquals(0.0, income.totalDevengado, 0.0)
        assertNull(income.evidence!!.document.gross)
    }

    @Test fun `invalid lines are retained for review and quantity is not invented`() {
        val source = valid().copy(lines = valid().lines + ScannedLine("Unreadable", null, null, null, 0.0))
        val result = DocumentValidator.validate(source)
        assertEquals(2, result.document.lines.size)
        assertNull(result.document.lines.last().quantity)
        assertTrue(result.issues.any { it.field == "lines.1.quantity" })
    }

    @Test fun `missing line VAT inherits only demonstrated uniform tax`() {
        val result = DocumentValidator.validate(valid().copy(lines = valid().lines.map { it.copy(vatPercent = null) }))
        assertTrue(result.issues.isEmpty())
        assertEquals(21.0, result.document.lines.single().vatPercent!!, 0.0)
        assertTrue(result.derivedFields.contains("lines.0.vatPercent"))
        val unknown = DocumentValidator.validate(valid().copy(taxBase = null, lines = listOf(valid().lines.single().copy(vatPercent = null))))
        assertTrue(unknown.issues.isEmpty())
        assertNull(unknown.document.lines.single().vatPercent)
    }

    @Test fun `invalid tax remains invalid instead of inherited`() {
        assertTrue(DocumentValidator.validate(valid().copy(lines = valid().lines.map { it.copy(vatPercent = 150.0) })).issues.any { it.field == "lines.0.vatPercent" })
        assertFalse(DocumentValidator.validate(valid().copy(vatPercent = Double.NaN)).issues.isEmpty())
    }

    @Test fun `derivation uses observed quantity without dropping rows`() {
        val result = DocumentValidator.validate(valid().copy(lines = listOf(ScannedLine("Item", 2.0, null, 121.0, 21.0))))
        assertTrue(result.issues.isEmpty())
        assertEquals(60.5, result.document.lines.single().unitPrice!!, 0.0)
        assertTrue(result.derivedFields.contains("lines.0.unitPrice"))
    }

    @Test fun `tax excluded products and withholding compare compatible concepts`() {
        val source = valid().copy(total = 106.0, withholdingPercent = 15.0, withholdingAmount = 15.0, priceBasis = "tax_excluded",
            lines = listOf(ScannedLine("Service", 1.0, 100.0, 100.0, 21.0)))
        assertTrue(DocumentValidator.validate(source).issues.isEmpty())
        assertEquals(21.0, DocumentEvidence(source).toInvoice("uuid", "photo").second.single().ivaAmount!!, 0.0)
    }

    @Test fun `decimal tolerance is two minor currency units`() {
        assertTrue(DocumentValidator.validate(valid().copy(total = 121.02)).issues.isEmpty())
        assertTrue(DocumentValidator.validate(valid().copy(total = 121.03)).issues.any { it.reason == ReviewReason.INCONSISTENT })
        assertTrue(DocumentValidator.validate(valid().copy(currency = "CLP", total = 123.0)).issues.isEmpty())
        assertFalse(DocumentValidator.validate(valid().copy(currency = "CLP", total = 124.0)).issues.isEmpty())
    }

    @Test fun `discounts and incomplete row coverage are explicit`() {
        assertTrue(DocumentValidator.validate(valid().copy(total = 111.0, taxBase = null, discount = 10.0)).issues.isEmpty())
        assertTrue(DocumentValidator.validate(valid().copy(linesComplete = false)).issues.any { it.reason == ReviewReason.INCOMPLETE })
    }

    @Test fun `issued invoice to income preserves fiscal details and products`() {
        val source = valid().copy(kind = "factura_emitida")
        val income = DocumentEvidence(source).toInvoice("uuid", "photo").first.toIncome()
        assertEquals("F-0001", income.evidence!!.document.number)
        assertEquals("ES-B123", income.evidence!!.document.issuerTaxId)
        assertEquals(1, income.evidence!!.document.lines.size)
        assertEquals(100.0, income.evidence!!.document.taxBase!!, 0.0)
    }

    @Test fun `evidence codec preserves absent original derived and manually corrected data`() {
        val evidence = DocumentEvidence(valid(), sourceSha256 = "hash", originalExtraction = "original", correctedFields = setOf("date"), derivedFields = setOf("quantity"))
        assertEquals(evidence, DocumentEvidenceCodec.decode(DocumentEvidenceCodec.encode(evidence)))
    }

    @Test fun `invalid optional numeric text cannot be accepted as absence`() {
        val source = DocumentEvidence(valid(), invalidFields = setOf("socialSecurity"))
        assertTrue(DocumentValidator.validate(source).issues.any { it.field == "socialSecurity" })
    }
}
