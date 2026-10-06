package com.gastos.feature.ai

import org.junit.Assert.*
import org.junit.Test

class PdfDocumentResponseTest {
    @Test fun oneDocumentMaySpanPagesButIndependentDocumentsStayDistinct() {
        assertEquals(1, PdfDocumentResponse.count("""{"document_count":1,"total":121}"""))
        assertEquals(2, PdfDocumentResponse.count("""{"document_count":2,"total":242}"""))
    }
    @Test fun missingAndMalformedCountsAreNotAcceptedAsOne() {
        listOf("{}", """{"document_count":0}""", """{"document_count":1.5}""", """{"document_count":"1"}""", """{"document_count":null}""").forEach { raw ->
            assertTrue(raw, runCatching { PdfDocumentResponse.count(raw) }.isFailure)
        }
    }
}
