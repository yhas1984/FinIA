package com.gastos.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * País fiscal y moneda son dos ajustes independientes: el país decide las reglas fiscales y la
 * moneda solo cómo se muestran los importes. Estas pruebas fijan las dos cosas: que la sugerencia
 * existe donde tiene sentido y que NO se fuerza ninguna equivalencia país-moneda.
 */
class FiscalCountryCurrencyTest {

    @Test
    fun `la lista de paises fiscales cubre los catorce con reglas cargadas`() {
        val esperados = listOf("ES", "US", "MX", "AR", "CO", "CL", "PE", "EC", "BO", "GT", "NI", "PY", "UY", "VE")
        assertEquals(esperados, SUPPORTED_FISCAL_COUNTRIES)
    }

    @Test
    fun `cada pais ofrece su moneda habitual como sugerencia`() {
        assertEquals("EUR", usualCurrencyForCountry("ES"))
        assertEquals("MXN", usualCurrencyForCountry("MX"))
        assertEquals("ARS", usualCurrencyForCountry("AR"))
        assertEquals("USD", usualCurrencyForCountry("EC"))
        assertEquals("UYU", usualCurrencyForCountry("UY"))
    }

    @Test
    fun `venezuela no sugiere moneda porque se factura en bolivares y en dolares`() {
        assertNull(usualCurrencyForCountry("VE"))
    }

    @Test
    fun `la sugerencia admite el codigo en minusculas y no inventa paises`() {
        assertEquals("MXN", usualCurrencyForCountry("mx"))
        assertNull(usualCurrencyForCountry("JP"))
    }

    @Test
    fun `las monedas sugeridas existen en la lista de monedas soportadas`() {
        SUPPORTED_FISCAL_COUNTRIES
            .mapNotNull { usualCurrencyForCountry(it) }
            .forEach { moneda -> assertTrue("Falta $moneda en SUPPORTED_CURRENCIES", moneda in SUPPORTED_CURRENCIES) }
    }

    @Test
    fun `la etiqueta del pais lleva nombre y codigo`() {
        assertEquals("España (ES)", fiscalCountryLabel("ES"))
        assertEquals("México (MX)", fiscalCountryLabel("mx"))
        // Un país sin nombre conocido no se inventa: se muestra el código.
        assertEquals("JP", fiscalCountryLabel("JP"))
    }
}
