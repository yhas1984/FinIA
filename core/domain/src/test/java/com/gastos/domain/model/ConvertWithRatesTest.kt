package com.gastos.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La conversión de cada fila del listado usa la misma regla que el informe:
 * si falta una tasa, no se inventa un número — se deja el importe original.
 */
class ConvertWithRatesTest {

    private val rates = mapOf("EUR" to 0.890292, "MXN" to 18.631602, "USD" to 1.0, "VES" to 884.7)

    @Test
    fun `misma moneda devuelve el importe intacto`() {
        assertEquals(3.5, convertWithRates(3.5, "EUR", "EUR", rates)!!, 0.0)
        assertEquals(3.5, convertWithRates(3.5, "eur", "EUR", rates)!!, 0.0)
    }

    @Test
    fun `convierte de euros a pesos con el par de tasas`() {
        val converted = convertWithRates(100.0, "EUR", "MXN", rates)!!
        assertEquals(100.0 * 18.631602 / 0.890292, converted, 1e-9)
        // 100 EUR son ~2.092,75 MXN: multiplica, no divide.
        assertEquals(2092.75, converted, 0.01)
    }

    @Test
    fun `los totales de la lista cuadran con el total del encabezado`() {
        val filas = listOf(3.5, 2.6, 20.0, 6.0, 2.36, 18.92)
        val sumaConvertida = filas.map { convertWithRates(it, "EUR", "MXN", rates)!! }.sum()
        val sumaOriginal = filas.sum()
        assertEquals(sumaOriginal * 18.631602 / 0.890292, sumaConvertida, 1e-9)
    }

    @Test
    fun `sin la tasa de origen no hay conversion`() {
        assertNull(convertWithRates(10.0, "GBP", "MXN", rates))
        assertNull(convertWithRates(10.0, "EUR", "JPY", rates))
    }

    @Test
    fun `una tasa invalida no produce un importe`() {
        assertNull(convertWithRates(10.0, "EUR", "MXN", rates + ("MXN" to 0.0)))
        assertNull(convertWithRates(10.0, "EUR", "MXN", rates + ("EUR" to -1.0)))
        assertNull(convertWithRates(10.0, "EUR", "MXN", rates + ("MXN" to Double.NaN)))
    }

    @Test
    fun `sin tasas cargadas no se convierte nada`() {
        assertNull(convertWithRates(10.0, "EUR", "MXN", emptyMap()))
    }

    @Test
    fun `el signo no cambia la conversion`() {
        val negativo = convertWithRates(-3.5, "EUR", "MXN", rates)!!
        assertEquals(-73.25, negativo, 0.01)
        assertEquals(convertWithRates(3.5, "EUR", "MXN", rates)!!, -negativo, 1e-9)
    }
}
