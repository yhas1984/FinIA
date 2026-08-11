package com.gastos.feature.ai

import android.content.Context
import com.gastos.repository.CountryFiscalConfigRepository
import com.gastos.repository.CurrencyPreference
import io.mockk.mockk
import io.mockk.every
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AIServiceInvoiceParsingTest {
    @Test
    fun `invoice parsing keeps fiscal identity and product details`() {
        val result = service().parseInvoiceResponse(
            responseText = """
                {
                  "tipo_documento":"factura_recibida",
                  "pais":"ES",
                  "moneda":"EUR",
                  "fecha":"2026-08-11",
                  "numero_factura":"F-2026-001",
                  "proveedor":"Mercado Central S.L.",
                  "categoria":"Alimentación",
                  "subcategoria":"Supermercado",
                  "nif_emisor":"B12345678",
                  "nif_receptor":"12345678Z",
                  "base_imponible":100.0,
                  "tipo_iva":21.0,
                  "cuota_iva":21.0,
                  "retencion_irpf":0.0,
                  "total":121.0,
                  "devengado":0.0,
                  "liquido":0.0,
                  "base_cotizacion":0.0,
                  "seguridad_social":0.0,
                  "productos":[{"descripcion":"Café","cantidad":2,"precio_unitario":3.5,"subtotal":7.0,"iva_percent":21.0}]
                }
            """.trimIndent(),
            imageUri = "content://invoice/1",
            fiscalCountry = "ES",
            defaultCurrency = "EUR"
        )

        assertTrue(result.success)
        val invoice = result.invoice
        assertNotNull(invoice)
        assertEquals("F-2026-001", invoice?.numeroFactura)
        assertEquals("B12345678", invoice?.nifEmisor)
        assertEquals("12345678Z", invoice?.nifReceptor)
        assertEquals(100.0, invoice?.baseImponible ?: 0.0, 0.001)
        assertEquals(21.0, invoice?.cuotaIva ?: 0.0, 0.001)
        assertEquals("Alimentación", invoice?.categoria)
        assertEquals("Supermercado", invoice?.subcategoria)
        assertEquals(1, result.products.size)
        assertEquals("Café", result.products.single().descripcion)
    }

    @Test
    fun `unreadable fiscal fields remain nullable instead of using fake text`() {
        val result = service().parseInvoiceResponse(
            responseText = """
                {"tipo_documento":"ticket","pais":"XX","moneda":"EUR","fecha":"",
                 "numero_factura":"","proveedor":"Tienda","categoria":"Otros",
                 "nif_emisor":"","nif_receptor":"","base_imponible":null,
                 "tipo_iva":0,"cuota_iva":null,"retencion_irpf":0,"total":5,
                 "devengado":null,"liquido":null,"base_cotizacion":null,"seguridad_social":null,
                 "productos":[]}
            """.trimIndent(),
            imageUri = "content://invoice/2",
            fiscalCountry = "ES",
            defaultCurrency = "EUR"
        )

        assertTrue(result.success)
        assertNull(result.invoice?.numeroFactura)
        assertNull(result.invoice?.nifEmisor)
        assertNull(result.invoice?.nifReceptor)
        assertNull(result.invoice?.baseImponible)
        assertNull(result.invoice?.cuotaIva)
    }

    @Test
    fun `explicit zero tax and locale numbers are parsed correctly`() {
        val result = service().parseInvoiceResponse(
            responseText = """
                {
                  "tipo_documento":"factura_recibida",
                  "pais":"ES",
                  "moneda":"EUR",
                  "fecha":"2026-08-11",
                  "proveedor":"Panadería",
                  "total":"1.234,56",
                  "tipo_iva":"0",
                  "productos":[
                    {"descripcion":"Pan","cantidad":"2","precio_unitario":"1,50","subtotal":"3,00","iva_percent":"0"}
                  ]
                }
            """.trimIndent(),
            imageUri = "content://invoice/3",
            fiscalCountry = "ES",
            defaultCurrency = "EUR"
        )

        assertTrue(result.success)
        assertEquals(1234.56, result.invoice?.total ?: 0.0, 0.001)
        assertEquals(0.0, result.invoice?.ivaPercent ?: -1.0, 0.001)
        assertEquals(1, result.products.size)
        assertEquals(0.0, result.products.single().ivaPercent, 0.001)
        assertEquals(1.5, result.products.single().precioUnitario, 0.001)
    }

    @Test
    fun `missing or invalid totals fail closed for manual review`() {
        val result = service().parseInvoiceResponse(
            responseText = """
                {
                  "tipo_documento":"nomina",
                  "empresa":"ACME",
                  "fecha":"2026-08-11",
                  "total":"unknown",
                  "devengado":null,
                  "liquido":""
                }
            """.trimIndent(),
            imageUri = "content://invoice/4",
            fiscalCountry = "ES",
            defaultCurrency = "EUR"
        )

        assertTrue(!result.success)
        assertTrue(result.message.contains("Revisión manual"))
    }

    @Test
    fun `english locale builds english prompts with stable json contract`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            val service = service()
            val method = AIService::class.java.getDeclaredMethod("buildEnglishSystemPrompt", String::class.java, String::class.java, String::class.java)
            method.isAccessible = true
            val prompt = method.invoke(service, "2026-08-11", "EUR", "") as String
            assertTrue(prompt.contains("\"query_type\":\"gastos|ingresos|balance|productos|productos_por_comercio\""))
            assertTrue(prompt.contains("\"periodo\":\"hoy|semana|mes|año\""))
            assertTrue(prompt.contains("products_by_store") || prompt.contains("productos_por_comercio"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `invalid product numerics are omitted instead of zeroed`() {
        val result = service().parseInvoiceResponse(
            responseText = """
                {
                  "tipo_documento":"factura_recibida",
                  "pais":"ES",
                  "moneda":"EUR",
                  "fecha":"2026-08-11",
                  "proveedor":"Tienda",
                  "total":10,
                  "productos":[
                    {"descripcion":"Bueno","cantidad":1,"precio_unitario":10,"subtotal":10,"iva_percent":21},
                    {"descripcion":"Malo","cantidad":"unknown","precio_unitario":null,"subtotal":""}
                  ]
                }
            """.trimIndent(),
            imageUri = "content://invoice/5",
            fiscalCountry = "ES",
            defaultCurrency = "EUR"
        )

        assertTrue(result.success)
        assertEquals(1, result.products.size)
        assertEquals("Bueno", result.products.single().descripcion)
    }

    private fun service(): AIService {
        val context = mockk<Context>(relaxed = true)
        every { context.getString(R.string.ai_manual_review_payroll) } returns "Revisión manual: importe de nómina ausente, inválido o no positivo"
        return AIService(
            context = context,
        fiscalConfigRepository = mockk<CountryFiscalConfigRepository>(relaxed = true),
        geminiRestClient = mockk<GeminiRestClient>(relaxed = true),
        currencyPreference = mockk<CurrencyPreference>(relaxed = true)
        )
    }
}
