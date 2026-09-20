package com.gastos.feature.ai

import com.gastos.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class AIServiceInvoiceParsingTest {
    @Test fun `international tax summaries parse without a single VAT and without allocating products`() {
        val json = source().put("moneda", "CAD").put("pais", "CA").put("total", 112).put("cuota_iva", JSONObject.NULL)
            .put("tipo_iva", JSONObject.NULL).put("impuestos_completos", true)
            .put("impuestos", org.json.JSONArray("""[
                {"nombre":"GST","porcentaje":5,"base":100,"importe":5,"tratamiento":"TAXABLE","efecto":"CHARGE"},
                {"nombre":"PST","porcentaje":7,"base":100,"importe":7,"tratamiento":"TAXABLE","efecto":"CHARGE"}]
            """))
        json.getJSONArray("productos").getJSONObject(0).put("iva_percent", JSONObject.NULL).put("precio_unitario", 112).put("subtotal", 112)
        val read = parse(json) as DocumentReadResult.Ready
        assertNull(read.evidence.document.vatPercent)
        assertNull(read.evidence.document.lines.single().vatPercent)
        assertEquals(2, read.evidence.document.taxes.size)
        assertEquals(12.0, read.evidence.document.vatAmount!!, 0.0)
        val invoice = read.evidence.toInvoice("uuid", "photo").first
        assertEquals(112.0, invoice.total, 0.0)
        assertEquals("GST", invoice.taxes.first().name)
    }

    @Test fun `malformed tax rows and unsupported treatments are never silently discarded`() {
        for (rows in listOf("[4]", "{}", "[{\"nombre\":\"VAT\",\"importe\":\"abc\",\"efecto\":\"CHARGE\"}]",
            "[{\"nombre\":\"VAT\",\"importe\":21,\"tratamiento\":\"unexpected\",\"efecto\":\"CHARGE\"}]")) {
            val json = source().put("impuestos_completos", true).put("impuestos", org.json.JSONTokener(rows).nextValue())
            val read = parse(json) as DocumentReadResult.NeedsReview
            assertTrue(read.evidence.invalidFields.any { it.startsWith("taxes") })
        }
    }

    private fun source(): JSONObject = JSONObject("""{
        "tipo_documento":"factura_recibida","pais":"ES","moneda":"EUR","fecha":"2026-09-19",
        "numero_factura":"F-0001","proveedor":"Synthetic Store","nif_emisor":"B123","nif_receptor":"P456",
        "base_imponible":100,"cuota_iva":21,"tipo_iva":21,"retencion_irpf":0,"total":121,
        "precios_impuestos":"tax_included","productos_completos":true,
        "productos":[{"descripcion":"Coffee","cantidad":1,"precio_unitario":121,"subtotal":121,"iva_percent":21}]
    }""")
    private fun parse(json: JSONObject): DocumentReadResult = DocumentReader.parse(json.toString())
    private fun evidence(result: DocumentReadResult): DocumentEvidence = when (result) {
        is DocumentReadResult.Ready -> result.evidence
        is DocumentReadResult.NeedsReview -> result.evidence
        else -> error("Expected parsed evidence")
    }

    @Test fun `invoice parsing keeps all fiscal identity and product details`() {
        val result = parse(source())
        assertTrue(result is DocumentReadResult.Ready)
        val (invoice, products) = evidence(result).toInvoice("uuid", "photo")
        assertEquals("F-0001", invoice.numeroFactura)
        assertEquals("B123", invoice.nifEmisor)
        assertEquals("P456", invoice.nifReceptor)
        assertEquals(100.0, invoice.baseImponible!!, 0.0)
        assertEquals(21.0, invoice.cuotaIva!!, 0.0)
        assertEquals("Coffee", products.single().descripcion)
    }

    @Test fun `explicit zero is retained with general 21 percent even when review is needed`() {
        val json = source()
        json.getJSONArray("productos").getJSONObject(0).put("iva_percent", 0)
        assertEquals(0.0, evidence(parse(json)).document.lines.single().vatPercent!!, 0.0)
    }

    @Test fun `missing line tax inherits only unambiguous general rate and invalid rate stays a doubt`() {
        val json = source()
        json.getJSONArray("productos").getJSONObject(0).remove("iva_percent")
        assertEquals(21.0, evidence(parse(json)).document.lines.single().vatPercent!!, 0.0)
        json.getJSONArray("productos").getJSONObject(0).put("iva_percent", 150)
        assertTrue(parse(json) is DocumentReadResult.NeedsReview)
        assertEquals(150.0, evidence(parse(json)).document.lines.single().vatPercent!!, 0.0)
    }

    @Test fun `missing date or unsupported currency is a reviewable result not today's date or default currency`() {
        val json = source().put("fecha", "").put("moneda", "XYZ")
        val result = parse(json) as DocumentReadResult.NeedsReview
        assertNull(result.evidence.document.date)
        assertEquals("XYZ", result.evidence.document.currency)
        assertTrue(result.issues.any { it.field == "date" })
        assertTrue(result.issues.any { it.field == "currency" })
    }

    @Test fun `missing or malformed product rows are never omitted`() {
        val json = source()
        json.getJSONArray("productos").put(JSONObject("""{"descripcion":"Unreadable","cantidad":null,"precio_unitario":null,"subtotal":null}"""))
        val result = parse(json) as DocumentReadResult.NeedsReview
        assertEquals(2, result.evidence.document.lines.size)
        assertNull(result.evidence.document.lines.last().quantity)
        assertTrue(result.issues.any { it.field == "lines.1.quantity" })
    }

    @Test fun `decimal numeric strings work without locale grouping guesses`() {
        val json = source().put("total", "121,00")
        assertTrue(parse(json) is DocumentReadResult.Ready)
        val result = parse(json.put("total", "1.234,56")) as DocumentReadResult.NeedsReview
        assertTrue("total" in result.evidence.invalidFields)
        assertEquals("1.234,56", result.evidence.fieldEdits["total"])
    }

    @Test fun `malformed optional tax is not hidden as absent`() {
        val result = parse(source().put("retencion_irpf", "unknown")) as DocumentReadResult.NeedsReview
        assertTrue("withholdingPercent" in result.evidence.invalidFields)
    }

    @Test fun `payroll without net does not use gross or total`() {
        val result = parse(source().put("tipo_documento", "nomina").put("devengado", 3000).put("liquido", JSONObject.NULL)) as DocumentReadResult.NeedsReview
        assertNull(result.evidence.document.net)
        assertEquals(3000.0, result.evidence.document.gross!!, 0.0)
        assertTrue(result.issues.any { it.field == "net" })
    }

    @Test fun `payroll identity and contribution data survive parsing`() {
        val result = parse(source().put("tipo_documento", "nomina").put("devengado", 3000).put("liquido", 2400)
            .put("referencia_nomina", "PAY-09").put("identificador_trabajador", "W-01")
            .put("periodo_liquidacion", "2026-09").put("tipo_pago", "ordinary").put("base_cotizacion", 3000).put("seguridad_social", 150))
        val document = evidence(result).document
        assertEquals("PAY-09", document.payrollReference)
        assertEquals("2026-09", document.payPeriod)
        assertEquals("W-01", document.workerId)
        assertEquals(150.0, document.socialSecurity!!, 0.0)
    }

    @Test fun `inconsistent valid JSON is parsed successfully for review not automatically retried`() {
        val response = source().put("total", 200).toString()
        assertTrue(runCatching { DocumentReader.parse(response) }.isSuccess)
        assertTrue(DocumentReader.parse(response) is DocumentReadResult.NeedsReview)
        assertFalse(runCatching { DocumentReader.parse("{broken") }.isSuccess)
    }

    @Test fun `unreadable fiscal identity remains absent`() {
        val result = parse(source().put("numero_factura", "").put("nif_emisor", "").put("pais", ""))
        assertNull(evidence(result).document.number)
        assertNull(evidence(result).document.issuerTaxId)
        assertNull(evidence(result).document.country)
    }
}
