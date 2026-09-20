package com.gastos.feature.chatbot

import com.gastos.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class DocumentEditorTest {
    @Test fun `nested tax evidence is displayed and corrected without dropping components`() {
        val taxes = listOf(DocumentTax("GST", 5.0, 100.0, 5.0, TaxTreatment.TAXABLE), DocumentTax("PST", 7.0, 100.0, 7.0, TaxTreatment.TAXABLE))
        val source = DocumentEvidence(ScannedDocument(taxes = taxes, lines = listOf(ScannedLine("Product", taxes = taxes))))
        assertEquals("PST", DocumentEditor.fields(source, Locale.ENGLISH)["lines.0.taxes.1.name"])
        val changed = DocumentEditor.edit(source, "taxes.0.amount", "4,50", Locale.forLanguageTag("es-ES"))
        assertEquals(4.5, changed.document.taxes.first().amount!!, 0.0)
        assertEquals(taxes.last(), changed.document.taxes.last())
        assertEquals(taxes, changed.document.lines.single().taxes)
    }
    @Test fun `Spanish decimals persist across draft reopening and preserve original evidence`() {
        val original = DocumentEvidence(ScannedDocument(total = 12.0), originalExtraction = "original")
        val edited = DocumentEditor.edit(original, "total", "12,50", Locale.forLanguageTag("es-ES"))
        assertEquals(12.5, edited.document.total!!, 0.0)
        val reopened = DocumentEvidenceCodec.decode(DocumentEvidenceCodec.encode(edited))!!
        assertEquals("12,50", DocumentEditor.fields(reopened, Locale.forLanguageTag("es-ES"))["total"])
        assertEquals("original", reopened.originalExtraction)
        assertTrue("total" in reopened.correctedFields)
    }
    @Test fun `invalid optional values remain visible and block save`() {
        val edited = DocumentEditor.edit(DocumentEvidence(ScannedDocument()), "withholdingPercent", "abc", Locale.ENGLISH)
        assertEquals("abc", DocumentEditor.fields(edited, Locale.ENGLISH)["withholdingPercent"])
        assertTrue(DocumentValidator.validate(edited).issues.any { it.field == "withholdingPercent" })
        val corrected = DocumentEditor.edit(edited, "withholdingPercent", "0", Locale.ENGLISH)
        assertFalse("withholdingPercent" in corrected.invalidFields)
        assertEquals(0.0, corrected.document.withholdingPercent!!, 0.0)
    }
    @Test fun `manual product edits preserve other lines and reset duplicate consent`() {
        val source = DocumentEvidence(ScannedDocument(lines = listOf(ScannedLine("A", 1.0), ScannedLine("B", 2.0))), distinctFrom = setOf("uuid:1"))
        val edited = DocumentEditor.edit(source, "lines.0.quantity", "3", Locale.ENGLISH)
        assertEquals(3.0, edited.document.lines.first().quantity!!, 0.0)
        assertEquals(source.document.lines.last(), edited.document.lines.last())
        assertTrue(edited.distinctFrom.isEmpty())
    }
    @Test fun `fast benchmark requires 25 documents twenty percent and no accuracy or coverage loss`() {
        val grade = BenchmarkGrade(800, 1000, true, true, true, true, false, false)
        assertFalse(summarizeBenchmark(List(24) { grade }).eligible)
        assertTrue(summarizeBenchmark(List(25) { grade }).eligible)
        assertFalse(summarizeBenchmark(List(25) { grade.copy(fastMs = 801) }).eligible)
        assertFalse(summarizeBenchmark(List(24) { grade } + grade.copy(fastAccurate = false)).eligible)
        assertFalse(summarizeBenchmark(List(24) { grade } + grade.copy(fastComplete = false)).eligible)
        assertFalse(summarizeBenchmark(List(24) { grade } + grade.copy(fastReview = true)).eligible)
    }
}
