package com.gastos.attribution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El parseo del referrer es la única parte de la atribución que se puede probar sin Play ni
 * dispositivo: por eso vive en Kotlin puro y se cubre aquí.
 */
class InstallAttributionParseTest {

    @Test
    fun `parsea el referrer real que emite finkit`() {
        // El enlace de finkit.eu codifica el referrer así (dos niveles de url-encoding).
        val raw = "utm_source%3Dfinkit%26utm_campaign%3Dcuanto-me-queda"
        val params = InstallAttribution.parseReferrerParams(raw)

        assertEquals("finkit", params["utm_source"])
        assertEquals("cuanto-me-queda", params["utm_campaign"])
    }

    @Test
    fun `referrer vacio o nulo no produce datos`() {
        assertTrue(InstallAttribution.parseReferrerParams(null).isEmpty())
        assertTrue(InstallAttribution.parseReferrerParams("").isEmpty())
    }

    @Test
    fun `los parametros de Play se ignoran`() {
        // Play añade metadatos propios (gclid, $...); no deben confundirse con utm_*.
        val raw = "utm_source=google-play&gclid=abc&\$id=xyz&utm_medium=organic"
        val params = InstallAttribution.parseReferrerParams(raw)

        assertEquals("google-play", params["utm_source"])
        assertEquals("organic", params["utm_medium"])
        // `$id` (literal) no entra; gclid sí es un parámetro legítimo de campaña.
        assertTrue(params.containsKey("gclid"))
    }

    @Test
    fun `un referrer roto no lanza excepciones`() {
        val params = InstallAttribution.parseReferrerParams("%%&&==&sin_valor&ok=1")
        assertEquals("1", params["ok"])
    }
}
