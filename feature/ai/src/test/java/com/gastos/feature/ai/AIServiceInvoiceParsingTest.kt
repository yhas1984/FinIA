package com.gastos.feature.ai

import android.content.Context
import com.gastos.repository.CountryFiscalConfigRepository
import com.gastos.repository.CurrencyPreference
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

    private fun service(): AIService = AIService(
        context = mockk<Context>(relaxed = true),
        fiscalConfigRepository = mockk<CountryFiscalConfigRepository>(relaxed = true),
        geminiRestClient = mockk<GeminiRestClient>(relaxed = true),
        currencyPreference = mockk<CurrencyPreference>(relaxed = true)
    )
}
