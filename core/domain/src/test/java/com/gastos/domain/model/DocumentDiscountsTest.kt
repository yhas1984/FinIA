package com.gastos.domain.model

import org.junit.Assert.*
import org.junit.Test

class DocumentDiscountsTest {
    private fun invoice(): ScannedDocument = ScannedDocument(
        kind = "factura_recibida", date = "2026-09-07", issuer = "Synthetic retailer", currency = "EUR",
        total = 649.99, taxBase = 537.18, vatAmount = 112.81, vatPercent = 21.0,
        taxes = listOf(DocumentTax("VAT", 21.0, 537.18, 112.81, TaxTreatment.TAXABLE)), taxesComplete = true,
        linesComplete = true, lines = listOf(ScannedLine("Synthetic product", 1.0, 991.73, 991.73, 21.0),
            ScannedLine("Descuento total", 1.0, -454.55, -454.55, 21.0)))

    @Test fun `explicit aggregate discount is not a negative product and printed amounts are preserved`() {
        val original = "original extraction with printed discount row"
        val (saved, products) = DocumentEvidence(invoice(), originalExtraction = original).toInvoice("same-uuid", "photo")
        assertEquals(649.99, saved.total, 0.0)
        assertEquals(537.18, saved.baseImponible!!, 0.0)
        assertEquals(112.81, saved.cuotaIva!!, 0.0)
        assertEquals(454.55, saved.evidence!!.document.discount!!, 0.0)
        assertEquals("tax_excluded", saved.evidence!!.document.priceBasis)
        assertEquals(1, products.size)
        assertEquals(991.73, products.single().precioUnitario, 0.0)
        assertEquals(original, saved.evidence!!.originalExtraction)
        assertTrue(saved.evidence!!.derivedFields.containsAll(listOf("discount", "lines")))
        assertTrue(DocumentValidator.validate(saved.evidence!!).issues.isEmpty())
    }

    @Test fun `repeated discount summary is applied only once`() {
        for (discount in listOf(null, 454.55)) {
            val result = DocumentValidator.validate(invoice().copy(discount = discount))
            assertTrue(result.issues.toString(), result.issues.isEmpty())
            assertEquals(454.55, result.document.discount!!, 0.0)
        }
    }

    @Test fun `unidentified negative rows inconsistent discounts and incomplete rows still block saving`() {
        val source = invoice()
        val discount = source.lines.last()
        val invalid = listOf(source.copy(discount = 10.0), source.copy(discount = 0.0), source.copy(lines = listOf(discount)), source.copy(linesComplete = false),
            source.copy(lines = source.lines.dropLast(1) + discount.copy(description = "Unidentified item")),
            source.copy(lines = source.lines.dropLast(1) + discount.copy(quantity = null)),
            source.copy(lines = source.lines.dropLast(1) + discount.copy(unitPrice = -100.0)),
            source.copy(lines = source.lines.dropLast(1) + discount.copy(vatPercent = 150.0)),
            source.copy(lines = source.lines + discount), source.copy(total = 900.0))
        invalid.forEach { assertTrue(DocumentValidator.validate(it).issues.isNotEmpty()) }
    }

    @Test fun `discount normalization is based on printed labels not country taxes`() {
        for (label in listOf("Total discount", "Remise totale", "Desconto total", "Sconto totale", "Totale korting")) {
            val result = DocumentValidator.validate(invoice().copy(country = null,
                lines = invoice().lines.dropLast(1) + invoice().lines.last().copy(description = label)))
            assertTrue(label, result.issues.isEmpty())
        }
    }

    @Test fun `discount preserves distinct tax groups without allocating an invented rate`() {
        val source = invoice().copy(total = 218.9, taxBase = 190.0, vatAmount = 28.9, vatPercent = null,
            taxes = listOf(DocumentTax("VAT", 21.0, 90.0, 18.9, TaxTreatment.TAXABLE),
                DocumentTax("VAT", 10.0, 100.0, 10.0, TaxTreatment.TAXABLE)),
            lines = listOf(ScannedLine("A", 1.0, 100.0, 100.0, 21.0), ScannedLine("B", 1.0, 100.0, 100.0, 10.0),
                ScannedLine("Descuento total", 1.0, -10.0, -10.0, 21.0)))
        val result = DocumentValidator.validate(source)
        assertTrue(result.issues.toString(), result.issues.isEmpty())
        assertEquals(source.taxes, result.document.taxes)
        assertNull(result.document.vatPercent)
        assertEquals(listOf(21.0, 10.0), result.document.lines.map { it.vatPercent })
    }

    @Test fun `printed discount taxes must also be coherent`() {
        val source = invoice()
        val discount = source.lines.last().copy(taxes = listOf(DocumentTax("VAT", 21.0, -454.55, -95.45, TaxTreatment.TAXABLE)))
        assertTrue(DocumentValidator.validate(source.copy(lines = source.lines.dropLast(1) + discount)).issues.isEmpty())
        val invalid = discount.copy(taxes = listOf(discount.taxes.single().copy(amount = -12.0)))
        assertFalse(DocumentValidator.validate(source.copy(lines = source.lines.dropLast(1) + invalid)).issues.isEmpty())
    }

    @Test fun `tax included discount uses the printed gross prices without changing the tax summary`() {
        val source = invoice().copy(lines = listOf(ScannedLine("Item", 1.0, 1199.99, 1199.99, 21.0),
            ScannedLine("Total discount", 1.0, -550.0, -550.0, 21.0)))
        val result = DocumentValidator.validate(source)
        assertTrue(result.issues.toString(), result.issues.isEmpty())
        assertEquals("tax_included", result.document.priceBasis)
        assertEquals(550.0, result.document.discount!!, 0.0)
        assertEquals(source.taxes, result.document.taxes)
        assertEquals(1199.99, result.document.lines.single().unitPrice!!, 0.0)
    }

    @Test fun `already separated discount accepts weighted products and shipping from different layouts`() {
        val source = ScannedDocument(kind = "ticket", date = "2026-09-21", issuer = "Synthetic grocery", currency = "GBP",
            total = 16.33, discount = 1.0, priceBasis = "tax_included", linesComplete = true,
            lines = listOf(ScannedLine("Weighted product", 4.125, 2.99, 12.33), ScannedLine("Delivery", 1.0, 5.0, 5.0)))
        val result = DocumentValidator.validate(source)
        assertTrue(result.issues.toString(), result.issues.isEmpty())
        assertEquals(source, result.document)
        assertNull(result.document.vatPercent)
        assertTrue(result.document.taxes.isEmpty())
    }
}
