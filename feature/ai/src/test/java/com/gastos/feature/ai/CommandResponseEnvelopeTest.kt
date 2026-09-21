package com.gastos.feature.ai

import org.junit.Assert.*
import org.junit.Test

class CommandResponseEnvelopeTest {
    @Test fun `actual fenced Gemini command records an expense without adding taxes`() {
        val raw = "```json\n{\"action\":\"add_expense\",\"descripcion\":\"TEST CAFE\",\"total\":12.50,\"tipo_iva\":null,\"productos\":[]}\n```"
        val parsed = requireNotNull(CommandResponseEnvelope.parse(raw))
        val (invoice, products) = CommandDocumentParser.expense(parsed, "EUR")
        assertEquals(12.5,invoice.total,0.0); assertNull(invoice.ivaPercent); assertTrue(products.isEmpty())
    }
    @Test fun `wrapped invalid explicit date still fails validation`() {
        val command = requireNotNull(CommandResponseEnvelope.parse("```JSON\n{\"action\":\"add_expense\",\"fecha\":\"2026-03-03\"}\n```"))
        assertTrue(runCatching { CommandDocumentParser.preserveExplicitDate(command,"Registra gasto el 31/02/2026") }.isFailure)
    }
    @Test fun `partial commands and trailing prose cannot become writes`() {
        for (text in listOf("{\"action\":", "```json\n{\"action\":\"add_income\"}", "{} trailing", "{}{}")) {
            assertTrue(text,runCatching { CommandResponseEnvelope.parse(text) }.isFailure)
        }
        assertNull(CommandResponseEnvelope.parse("Example of JSON: {\"action\":\"add_income\"}"))
        assertNull(CommandResponseEnvelope.parse("Hello"))
    }
}
