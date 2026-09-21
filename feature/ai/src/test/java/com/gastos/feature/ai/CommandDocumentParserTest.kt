package com.gastos.feature.ai

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CommandDocumentParserTest {
    @Test fun `missing or null categories stay absent for both command types`() {
        for (value: Any in listOf(JSONObject.NULL, "null", " NULL ", "")) {
            val json: JSONObject = JSONObject().put("descripcion", "Example").put("monto", 20)
                .put("categoria", value).put("subcategoria", value)
            assertNull(CommandDocumentParser.expense(json, "EUR").first.categoria)
            assertNull(CommandDocumentParser.expense(json, "EUR").first.subcategoria)
            assertNull(CommandDocumentParser.income(json, "EUR").categoria)
            assertNull(CommandDocumentParser.income(json, "EUR").subcategoria)
        }
    }
    @Test fun `provided categories and subcategories remain intact`() {
        val json: JSONObject = JSONObject().put("descripcion", "Example").put("monto", 20)
            .put("categoria", "Alimentación").put("subcategoria", "Supermercado")
        assertEquals("Alimentación", CommandDocumentParser.expense(json, "EUR").first.categoria)
        assertEquals("Supermercado", CommandDocumentParser.expense(json, "EUR").first.subcategoria)
        json.put("categoria", "Ventas").put("subcategoria", "Segunda mano")
        assertEquals("Ventas", CommandDocumentParser.income(json, "EUR").categoria)
        assertEquals("Segunda mano", CommandDocumentParser.income(json, "EUR").subcategoria)
    }
    @Test fun `unspecified taxes and products remain absent`() {
        val (invoice, products) = CommandDocumentParser.expense(JSONObject("""{"descripcion":"Lunch","monto":20}"""), "EUR")
        assertNull(invoice.ivaPercent)
        assertNull(invoice.cuotaIva)
        assertTrue(products.isEmpty())
    }
    @Test fun `explicit zero and explicit tax survive`() {
        for (rate in listOf(0.0, 4.0, 21.0)) {
            val invoice = CommandDocumentParser.expense(JSONObject().put("descripcion", "Lunch").put("monto", 20).put("tipo_iva", rate), "EUR").first
            assertEquals(rate, invoice.ivaPercent!!, 0.0)
        }
    }
    @Test fun `generic income has no invented payroll breakdown`() {
        val income = CommandDocumentParser.income(JSONObject("""{"concepto":"Gift","monto":100}"""), "EUR")
        assertEquals(0.0, income.totalDevengado, 0.0)
        assertEquals(0.0, income.totalNeto, 0.0)
        assertNull(income.ivaPercent)
    }
    @Test fun `invalid dates are not replaced or normalized`() {
        for (date in listOf("2026-02-31", "2026-13-01", "2026-09-01extra", "yesterday")) {
            assertTrue(runCatching { CommandDocumentParser.date(date) }.isFailure)
        }
        assertEquals(123L, CommandDocumentParser.date(null, 123L))
        assertNotNull(CommandDocumentParser.date("2024-02-29"))
    }
    @Test fun `invalid amounts rates and inconsistent products cannot be saved`() {
        val source = JSONObject("""{"descripcion":"Lunch","monto":20}""")
        assertTrue(runCatching { CommandDocumentParser.expense(source.put("tipo_iva", 150), "EUR") }.isFailure)
        source.remove("tipo_iva")
        assertTrue(runCatching { CommandDocumentParser.expense(source.put("monto", "Infinity"), "EUR") }.isFailure)
        source.put("monto", 20).put("productos", org.json.JSONArray("""[{"descripcion":"Food","cantidad":2,"precio_unitario":5,"subtotal":20}]"""))
        assertTrue(runCatching { CommandDocumentParser.expense(source, "EUR") }.isFailure)
    }
    @Test fun `null date means unspecified but explicit impossible date cannot be repaired by model`() {
        val json = JSONObject("""{"descripcion":"Lunch","monto":20,"fecha":null}""")
        assertTrue(CommandDocumentParser.expense(json,"EUR").first.fecha > 0)
        json.put("fecha","2026-03-03")
        assertTrue(runCatching { CommandDocumentParser.preserveExplicitDate(json,"Compra el 31/02/2026",java.util.Locale.forLanguageTag("es-ES")) }.isFailure)
        CommandDocumentParser.preserveExplicitDate(json,"Compra el 29/02/2024",java.util.Locale.forLanguageTag("es-ES"))
        assertEquals("2024-02-29",json.getString("fecha"))
        CommandDocumentParser.preserveExplicitDate(json,"Purchased on 09/21/2026",java.util.Locale.US)
        assertEquals("2026-09-21",json.getString("fecha"))
    }
    @Test fun `multiple tax components and withholding remain independent`() {
        val json = JSONObject("""{"descripcion":"Invoice","monto":32.1,"impuestos":[
            {"nombre":"IVA","porcentaje":21,"base":10,"importe":2.1,"tratamiento":"TAXABLE","efecto":"CHARGE"},
            {"nombre":"IVA","porcentaje":0,"base":20,"importe":0,"tratamiento":"ZERO_RATED","efecto":"CHARGE"},
            {"nombre":"Retencion","porcentaje":15,"base":10,"importe":1.5,"tratamiento":"TAXABLE","efecto":"WITHHOLDING"}]}""")
        val result = CommandDocumentParser.expense(json,"EUR").first
        assertNull(result.ivaPercent)
        assertEquals(3,result.taxes.size)
        assertEquals(0.0,result.taxes[1].rate!!,0.0)
        json.getJSONArray("impuestos").getJSONObject(0).put("importe",50)
        assertTrue(runCatching { CommandDocumentParser.expense(json,"EUR") }.isFailure)
    }

}
