package com.gastos.domain.model

import org.junit.Assert.*
import org.junit.Test

class DocumentProvenanceTest {
    private val capturedAt: Long = requireNotNull(DocumentValidator.parseDate("2026-10-05"))

    @Test fun missingDateAndCurrencyUseExplicitCaptureAndPreferenceOrigins() {
        val source = DocumentEvidence(ScannedDocument(kind = "ticket", issuer = "Synthetic store", total = 12.0))
        val result = DocumentProvenance.prepareCapture(source, "USD", capturedAt)
        assertEquals("2026-10-05", result.document.date)
        assertEquals("USD", result.document.currency)
        assertEquals(DocumentFieldOrigin.CAPTURE, result.fieldOrigins["date"])
        assertEquals(DocumentFieldOrigin.PREFERENCE, result.fieldOrigins["currency"])
        assertEquals(DocumentFieldOrigin.EXTRACTED, result.fieldOrigins["total"])
        assertNull(result.document.vatPercent)
        assertTrue(result.correctedFields.isEmpty())
    }

    @Test fun explicitDocumentValuesAlwaysTakePrecedenceOverPreferencesAndCaptureDate() {
        val source = DocumentEvidence(ScannedDocument(kind = "ticket", date = "2026-09-01", currency = "EUR", total = 12.0, vatPercent = 0.0))
        val result = DocumentProvenance.prepareCapture(source, "USD", capturedAt)
        assertEquals("2026-09-01", result.document.date)
        assertEquals("EUR", result.document.currency)
        assertEquals(0.0, result.document.vatPercent!!, 0.0)
        assertEquals(DocumentFieldOrigin.EXTRACTED, result.fieldOrigins["date"])
        assertEquals(DocumentFieldOrigin.EXTRACTED, result.fieldOrigins["currency"])
    }

    @Test fun invalidDateAndCurrencyAreNotPresentedAsExtractedFacts() {
        val source = DocumentEvidence(ScannedDocument(kind = "ticket", date = "2026-02-31", currency = "INVALID", total = 12.0))
        val result = DocumentProvenance.prepareCapture(source, "GBP", capturedAt)
        assertEquals("2026-10-05", result.document.date)
        assertEquals("GBP", result.document.currency)
        assertEquals(DocumentFieldOrigin.CAPTURE, result.fieldOrigins["date"])
        assertEquals(DocumentFieldOrigin.PREFERENCE, result.fieldOrigins["currency"])
    }

    @Test fun calculatedTaxFieldsAreDerivedAndNotLabelledAsPrintedValues() {
        val source = DocumentEvidence(ScannedDocument(kind = "ticket", date = "2026-10-05", currency = "EUR", total = 121.0,
            taxes = listOf(DocumentTax("IVA", 21.0, 100.0, 21.0, TaxTreatment.TAXABLE)), taxesComplete = true))
        val result = DocumentProvenance.prepareCapture(source, "EUR", capturedAt)
        assertEquals(100.0, result.document.taxBase!!, 0.0)
        assertEquals(21.0, result.document.vatAmount!!, 0.0)
        assertEquals(DocumentFieldOrigin.DERIVED, result.fieldOrigins["taxBase"])
        assertEquals(DocumentFieldOrigin.DERIVED, result.fieldOrigins["vatAmount"])
        assertEquals(DocumentFieldOrigin.DERIVED, result.fieldOrigins["vatPercent"])
        assertEquals(DocumentFieldOrigin.EXTRACTED, result.fieldOrigins["total"])
    }

    @Test fun editingOneValueToExplicitZeroMarksOnlyThatFieldManual() {
        val document = ScannedDocument(kind = "ticket", issuer = "Synthetic store", currency = "EUR", total = 20.0, vatPercent = 21.0)
        val previous = DocumentEvidence(document, fieldOrigins = mapOf("issuer" to DocumentFieldOrigin.WALLET,
            "total" to DocumentFieldOrigin.WALLET, "vatPercent" to DocumentFieldOrigin.EXTRACTED))
        val result = DocumentProvenance.markManual(previous, document.copy(vatPercent = 0.0))
        assertEquals(setOf("vatPercent"), result.correctedFields)
        assertEquals(DocumentFieldOrigin.MANUAL, result.fieldOrigins["vatPercent"])
        assertEquals(DocumentFieldOrigin.WALLET, result.fieldOrigins["issuer"])
        assertEquals(DocumentFieldOrigin.WALLET, result.fieldOrigins["total"])
        assertEquals(0.0, result.document.vatPercent!!, 0.0)
    }
}
