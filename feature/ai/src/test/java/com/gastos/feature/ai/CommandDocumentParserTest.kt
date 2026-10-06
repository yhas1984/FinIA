package com.gastos.feature.ai

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CommandDocumentParserTest {
    @Test fun `two coffees sharing one total do not reuse that total as unit price`() {
        val response: JSONObject = JSONObject("""{"descripcion":"Cafés","total":2.60,"productos":[
            {"descripcion":"Café","cantidad":2,"precio_unitario":2.60,"subtotal":2.60}]}""")
        val (invoice, products) = CommandDocumentParser.expense(response, "EUR", "agrega gasto 2 cafés por 2,60€ ambos")
        assertEquals(2.60, invoice.total, 0.0)
        assertEquals(2.0, products.single().cantidad, 0.0)
        assertEquals(1.30, products.single().precioUnitario, 0.0)
        assertEquals(2.60, products.single().subtotal, 0.0)
        assertNull(products.single().ivaPercent)
    }
    @Test fun `all reported Spanish messages preserve the shared amount even when the model multiplies it`() {
        val messages: List<String> = listOf("agrega gasto 2 café por 2,60€",
            "agrega gasto por 2 cafés en 2,60€ ambos", "agrega gasto 2 cafés por 2,60€ ambos")
        for (message: String in messages) {
            for (generatedTotal: Double in listOf(2.60, 5.20)) {
                val response: JSONObject = coffeeResponse().put("total", generatedTotal)
                val (invoice, products) = CommandDocumentParser.expense(response, "EUR", message)
                assertEquals(message, 2.60, invoice.total, 0.0)
                assertEquals(message, 1.30, products.single().precioUnitario, 0.0)
                assertEquals(message, invoice.total, products.single().subtotal, 0.0)
                assertNull(invoice.ivaPercent)
                assertNull(products.single().ivaPercent)
            }
        }
    }
    @Test fun `explicit unit price preserves the quantity and yields the full paid amount`() {
        val messages: List<String> = listOf("agrega gasto 2 cafés a 2,60€ cada uno",
            "agrega gasto 2 cafés por 2,60€ la unidad", "agrega gasto 2 cafés a 2,60€",
            "agrega gasto 2 cafés por 2,60€ por café", "agrega gasto 2 cafés por 2,60€/ud",
            "agrega gasto 2 cafés por 2,60€ c/u", "agrega gasto 2 cafés por 2,60€ cada  uno")
        for (message: String in messages) {
            val (invoice, products) = CommandDocumentParser.expense(coffeeResponse(), "EUR", message)
            assertEquals(message, 5.20, invoice.total, 0.0)
            assertEquals(message, 2.60, products.single().precioUnitario, 0.0)
            assertEquals(message, 5.20, products.single().subtotal, 0.0)
        }
    }
    @Test fun `English total and each wording works with prefix and suffix currencies`() {
        for ((message, currency, expected) in listOf(Triple("Add 2 coffees for EUR 2.60 both", "EUR", 2.60),
            Triple("Add 2 coffees for $2.60 both", "USD", 2.60),
            Triple("Add 2 coffees at £2.60 each", "GBP", 5.20),
            Triple("Add 2 coffees for 2.60 CAD each", "CAD", 5.20))) {
            val response: JSONObject = coffeeResponse().apply { getJSONArray("productos").getJSONObject(0).put("descripcion", "Coffee") }
            val (invoice, products) = CommandDocumentParser.expense(response, currency, message)
            assertEquals(message, expected, invoice.total, 0.0)
            assertEquals(message, currency, invoice.moneda)
            assertEquals(message, expected, products.single().cantidad * products.single().precioUnitario, 0.000001)
        }
    }
    @Test fun `derived repeating unit price never changes the original total`() {
        val response: JSONObject = coffeeResponse().apply { getJSONArray("productos").getJSONObject(0).put("cantidad", 3) }
        val (invoice, products) = CommandDocumentParser.expense(response, "EUR", "agrega gasto 3 cafés por 2,60€ en total")
        assertEquals(2.60, invoice.total, 0.0)
        assertEquals(invoice.total, products.single().cantidad * products.single().precioUnitario, 0.000001)
    }
    @Test fun `missing unit price can be derived only from explicit quantity and total`() {
        val response: JSONObject = coffeeResponse().apply { getJSONArray("productos").getJSONObject(0).put("precio_unitario", JSONObject.NULL) }
        val (invoice, products) = CommandDocumentParser.expense(response, "EUR", "agrega gasto 2 cafés por 2,60€ ambos")
        assertEquals(2.60, invoice.total, 0.0)
        assertEquals(1.30, products.single().precioUnitario, 0.0)
        assertThrows(InvalidCommandProductsException::class.java) { CommandDocumentParser.expense(response, "EUR") }
    }
    @Test fun `unrelated values unknown quantities and contradictory prices are not silently repaired`() {
        val badPrice: JSONObject = coffeeResponse().apply { getJSONArray("productos").getJSONObject(0).put("precio_unitario", 9.99) }
        assertThrows(InvalidCommandProductsException::class.java) { CommandDocumentParser.expense(badPrice, "EUR", "agrega gasto 2 cafés por 2,60€ ambos") }
        val badTotal: JSONObject = coffeeResponse().put("total", 20)
        assertThrows(InvalidCommandProductsException::class.java) { CommandDocumentParser.expense(badTotal, "EUR", "agrega gasto 2 cafés por 2,60€ ambos") }
        for (message: String in listOf("gasto 2,60€ en cafés", "gasto 2 cafés y una tostada por 2,60€",
            "gasto 2 cafés por 2,60€ cada uno ambos", "gasto 2 cafés por 2,60€ y propina 1€")) {
            assertThrows(message, InvalidCommandProductsException::class.java) { CommandDocumentParser.expense(coffeeResponse(), "EUR", message) }
        }
        val wrongQuantity: JSONObject = coffeeResponse().apply { getJSONArray("productos").getJSONObject(0).put("cantidad", 3) }
        assertThrows(InvalidCommandProductsException::class.java) { CommandDocumentParser.expense(wrongQuantity, "EUR", "gasto 2 cafés por 2,60€ ambos") }
    }
    @Test fun `grouped monetary input and two explicit prices are not guessed by the repair`() {
        for (message: String in listOf("2 cafés por 1,234 EUR ambos", "2 cafés por EUR 1.234,56 ambos",
            "2 cafés por 2,60€ total 5,20€")) {
            assertNull(CommandProductPricing.resolve(message, "EUR", "Café", 2.0))
        }
    }
    @Test fun `discounts and unqualified prices are left to the existing financial interpretation`() {
        val response: JSONObject = coffeeResponse().put("total", 1.30).apply {
            getJSONArray("productos").getJSONObject(0).put("precio_unitario", 0.65).put("subtotal", 1.30)
        }
        val (invoice, products) = CommandDocumentParser.expense(response, "EUR", "agrega gasto 2 cafés por 2,60€ con descuento 50%")
        assertEquals(1.30, invoice.total, 0.0)
        assertEquals(0.65, products.single().precioUnitario, 0.0)
        assertNull(CommandProductPricing.resolve("2 cafés de 2,60€", "EUR", "Café", 2.0))
        assertNull(CommandProductPricing.resolve("2 cafés 2,60€", "EUR", "Café", 2.0))
    }
    @Test fun `known percentage is preserved but monetary tax breakdown is never recalculated`() {
        val response: JSONObject = coffeeResponse().put("tipo_iva", 10).apply { getJSONArray("productos").getJSONObject(0).put("iva_percent", 10) }
        val (invoice, products) = CommandDocumentParser.expense(response, "EUR", "agrega gasto 2 cafés por 2,60€ ambos con IVA 10%")
        assertEquals(10.0, invoice.ivaPercent!!, 0.0)
        assertEquals(10.0, products.single().ivaPercent!!, 0.0)
        response.put("base_imponible", 2).put("cuota_iva", 0.6)
        assertThrows(InvalidCommandProductsException::class.java) { CommandDocumentParser.expense(response, "EUR", "agrega gasto 2 cafés por 2,60€ ambos") }
    }
    @Test fun `multiple products with valid prices retain all their individual amounts`() {
        val response: JSONObject = coffeeResponse().put("total", 4.60).apply {
            getJSONArray("productos").getJSONObject(0).put("precio_unitario", 1.30)
            getJSONArray("productos").put(JSONObject("""{"descripcion":"Toast","cantidad":1,"precio_unitario":2,"subtotal":2}"""))
        }
        val (invoice, products) = CommandDocumentParser.expense(response, "EUR", "agrega gasto 2 cafés y una tostada por 4,60€")
        assertEquals(2, products.size)
        assertEquals(4.60, invoice.total, 0.0)
        assertEquals(1.30, products.first().precioUnitario, 0.0)
        assertEquals(2.0, products.last().subtotal, 0.0)
    }

    private fun coffeeResponse(): JSONObject = JSONObject("""{"descripcion":"Cafés","total":2.60,"productos":[
        {"descripcion":"Café","cantidad":2,"precio_unitario":2.60,"subtotal":2.60}]}""")
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
