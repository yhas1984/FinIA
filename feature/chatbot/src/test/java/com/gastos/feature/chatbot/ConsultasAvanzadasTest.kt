package com.gastos.feature.chatbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ConsultasAvanzadasTest {

    private val zona = TimeZone.getTimeZone("Europe/Madrid")

    private fun momento(anio: Int, mes: Int, dia: Int): Long =
        Calendar.getInstance(zona).apply {
            clear()
            set(anio, mes - 1, dia, 12, 0, 0)
        }.timeInMillis

    private fun mov(mes: Int, importe: Double, comercio: String = "Mercadona",
                    categoria: String = "Alimentación", producto: String = "Banana") =
        ConsultasAvanzadas.Movimiento(momento(2026, mes, 10), importe, comercio, categoria, producto)

    @Test
    fun `un gasto por mes agrupa por mes y ordena del mas reciente al mas antiguo`() {
        val filas = ConsultasAvanzadas.porMes(
            listOf(mov(5, 3.0), mov(5, 2.0), mov(6, 4.0), mov(7, 1.0)), zona
        )
        assertEquals(3, filas.size)
        assertEquals("2026-07", filas[0].etiqueta)
        assertEquals(1.0, filas[0].importe, 0.001)
        assertEquals("2026-06", filas[1].etiqueta)
        assertEquals(4.0, filas[1].importe, 0.001)
        assertEquals("2026-05", filas[2].etiqueta)
        assertEquals(5.0, filas[2].importe, 0.001)
        assertEquals(2, filas[2].movimientos)
    }

    @Test
    fun `la media mensual cuenta solo los meses con movimientos`() {
        val filas = ConsultasAvanzadas.porMes(listOf(mov(5, 10.0), mov(6, 20.0), mov(8, 30.0)), zona)
        assertEquals(20.0, ConsultasAvanzadas.mediaMensual(filas), 0.001)
        assertEquals(0.0, ConsultasAvanzadas.mediaMensual(emptyList()), 0.001)
    }

    @Test
    fun `el resumen mensual responde cuanto gasto al mes en un producto`() {
        // 6 compras de bananas repartidas en 3 meses: 12 € en total, 4 €/mes de media
        val bananas = listOf(
            mov(5, 2.0), mov(5, 2.0), mov(6, 2.0), mov(6, 2.0), mov(7, 2.0), mov(7, 2.0)
        )
        val resumen = ConsultasAvanzadas.resumenMensual(bananas, zona)
        assertEquals(12.0, resumen.total, 0.001)
        assertEquals(6, resumen.compras)
        assertEquals(3, resumen.mesesConDatos)
        assertEquals(4.0, resumen.mediaPorMes, 0.001)
        assertEquals(3, resumen.meses.size)
    }

    @Test
    fun `el ranking ordena por importe y respeta el limite`() {
        val movimientos = listOf(
            mov(5, 50.0, categoria = "Transporte"),
            mov(5, 80.0, categoria = "Alimentación"),
            mov(5, 20.0, categoria = "Ocio"),
            mov(5, 5.0, categoria = "Salud")
        )
        val top = ConsultasAvanzadas.ranking(movimientos, { it.categoria }, limite = 3)
        assertEquals(listOf("Alimentación", "Transporte", "Ocio"), top.map { it.etiqueta })
        assertEquals(80.0, top.first().importe, 0.001)
    }

    @Test
    fun `el ranking agrupa lo que no tiene etiqueta en sin clasificar`() {
        val top = ConsultasAvanzadas.ranking(listOf(mov(5, 10.0, categoria = " ")), { it.categoria })
        assertEquals("Sin clasificar", top.first().etiqueta)
    }

    @Test
    fun `la comparacion calcula diferencia y porcentaje`() {
        val c = ConsultasAvanzadas.comparar(actual = 150.0, anterior = 100.0)
        assertEquals(50.0, c.diferencia, 0.001)
        assertEquals(50.0, c.porcentaje!!, 0.001)
        assertNull(ConsultasAvanzadas.comparar(10.0, 0.0).porcentaje)
    }

    @Test
    fun `mayor ultimo y total`() {
        val lista = listOf(mov(5, 10.0), mov(7, 30.0), mov(6, 20.0))
        assertEquals(30.0, ConsultasAvanzadas.mayor(lista)!!.importe, 0.001)
        assertEquals(momento(2026, 7, 10), ConsultasAvanzadas.ultimo(lista)!!.fecha)
        assertEquals(60.0, ConsultasAvanzadas.total(lista), 0.001)
        assertNull(ConsultasAvanzadas.mayor(emptyList()))
    }
}
