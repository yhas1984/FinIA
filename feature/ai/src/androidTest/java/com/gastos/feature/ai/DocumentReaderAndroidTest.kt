package com.gastos.feature.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gastos.domain.model.toInvoice
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise Android's regex and JSON implementations, not the desktop JVM substitutes. */
@RunWith(AndroidJUnit4::class)
class DocumentReaderAndroidTest {
    private val invoice: String = """{
        "tipo_documento":"factura_recibida","pais":"ES","moneda":"EUR","fecha":"2026-09-20",
        "numero_factura":"SYNTHETIC-001","proveedor":"Synthetic store",
        "base_imponible":100,"cuota_iva":21,"tipo_iva":21,"total":121,
        "precios_impuestos":"tax_excluded","productos_completos":true,
        "productos":[{"descripcion":"Synthetic item","cantidad":2,"precio_unitario":50,"subtotal":100,"iva_percent":21}]
    }"""

    @Test fun mixedZeroAnd21PercentDoesNotRequireAnInventedGlobalVat() {
        val source: JSONObject = JSONObject(invoice).put("base_imponible", 35).put("cuota_iva", 4.2)
            .put("total", 39.2).put("tipo_iva", JSONObject.NULL)
            .put("productos", org.json.JSONArray("""[
                {"descripcion":"Taxed item","cantidad":2,"precio_unitario":10,"subtotal":20,"iva_percent":21},
                {"descripcion":"Zero item","cantidad":1,"precio_unitario":15,"subtotal":15,"iva_percent":0}]
            """))
        val result: DocumentReadResult.Ready = DocumentReader.parse(source.toString()) as DocumentReadResult.Ready
        assertNull(result.evidence.document.vatPercent)
        assertEquals(0.0, result.evidence.document.lines.last().vatPercent!!, 0.0)
        val saved = result.evidence.toInvoice("synthetic", "photo").first
        assertNull(saved.ivaPercent)
        assertEquals(39.2, saved.total, 0.0)
    }

    @Test fun plainJsonKeepsPrintedFieldsOnAndroid() {
        val result: DocumentReadResult.Ready = DocumentReader.parse(invoice) as DocumentReadResult.Ready
        assertEquals("2026-09-20", result.evidence.document.date)
        assertEquals("SYNTHETIC-001", result.evidence.document.number)
        assertEquals(121.0, result.evidence.document.total!!, 0.0)
        assertEquals(21.0, result.evidence.document.vatAmount!!, 0.0)
        assertEquals(2.0, result.evidence.document.lines.single().quantity!!, 0.0)
    }

    @Test fun fencedJsonKeepsExplicitZeroAlongsideGeneralRate() {
        val source: JSONObject = JSONObject(invoice).put("base_imponible", 150).put("total", 171)
        source.getJSONArray("productos").put(JSONObject("""{"descripcion":"Zero tax item","cantidad":1,"precio_unitario":50,"subtotal":50,"iva_percent":0}"""))
        val result: DocumentReadResult.Ready = DocumentReader.parse("```json\n$source\n```") as DocumentReadResult.Ready
        assertEquals(0.0, result.evidence.document.lines.last().vatPercent!!, 0.0)
        assertEquals(21.0, result.evidence.document.vatPercent!!, 0.0)
        assertEquals(171.0, result.evidence.document.total!!, 0.0)
    }

    @Test fun missingDateStaysMissingAndRequiresReview() {
        val source: JSONObject = JSONObject(invoice).put("fecha", JSONObject.NULL)
        val result: DocumentReadResult.NeedsReview = DocumentReader.parse(source.toString()) as DocumentReadResult.NeedsReview
        assertNull(result.evidence.document.date)
        assertTrue(result.issues.any { it.field == "date" })
        assertEquals(121.0, result.evidence.document.total!!, 0.0)
    }

    @Test fun validOcrResponseDoesNotTriggerUnnecessaryModelFallback() = runBlocking {
        val calls: MutableList<String> = mutableListOf()
        val envelope: String = """{"candidates":[{"content":{"parts":[{"text":${JSONObject.quote(invoice)}}]},"finishReason":"STOP"}]}"""
        val client: GeminiRestClient = GeminiRestClient(GeminiTransport { _, _, model, _, _ ->
            calls.add(model)
            Response.Builder().request(Request.Builder().url("https://example.test").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("Synthetic response").body(envelope.toResponseBody()).build()
        }, {}, { 0L }, { _, _, _ -> })
        val request: GeminiGenerateRequest = GeminiGenerateRequest("synthetic-key", "test", listOf(
            GeminiContent("user", inlineDataParts = listOf(GeminiInlineDataPart("image/jpeg", "synthetic")))))
        val result: String = client.generateContent(request) { DocumentReader.parse(it) is DocumentReadResult.Ready }
        assertEquals(invoice, result)
        assertEquals(listOf(GeminiRestClient.PRIMARY_MODEL), calls)
    }
}
