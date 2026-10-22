package com.gastos.feature.chatbot

/**
 * Cálculos de los que el chat carecía: gasto por mes, media mensual, rankings,
 * comparación de periodos y extremos (mayor / último movimiento).
 *
 * Regla de oro del asistente: estas cuentas se hacen AQUÍ, en local, nunca en el modelo.
 * Todo son funciones puras sobre una lista de movimientos ya filtrada, para poder probarlas
 * sin Android de por medio.
 */
internal object ConsultasAvanzadas {

    /** Un movimiento reducido a lo que necesitan las cuentas. */
    data class Movimiento(
        val fecha: Long,
        val importe: Double,
        val comercio: String = "",
        val categoria: String = "",
        val producto: String = ""
    )

    /** Una fila de resultado: etiqueta (mes, categoría, comercio…), importe y nº de movimientos. */
    data class Fila(val etiqueta: String, val importe: Double, val movimientos: Int)

    private fun mesDe(fecha: Long, zona: java.util.TimeZone): String =
        java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.ROOT)
            .also { it.timeZone = zona }
            .format(java.util.Date(fecha))

    /** Agrupa por mes (de más reciente a más antiguo) con total y número de movimientos. */
    fun porMes(
        movimientos: List<Movimiento>,
        zona: java.util.TimeZone = java.util.TimeZone.getDefault()
    ): List<Fila> = movimientos
        .groupBy { mesDe(it.fecha, zona) }
        .map { (mes, lista) -> Fila(mes, lista.sumOf { it.importe }, lista.size) }
        .sortedByDescending { it.etiqueta }

    /** Media por mes teniendo en cuenta solo los meses en los que hubo movimientos. */
    fun mediaMensual(filas: List<Fila>): Double {
        val meses = filas.count { it.movimientos > 0 }
        if (meses == 0) return 0.0
        return filas.filter { it.movimientos > 0 }.sumOf { it.importe } / meses
    }

    /** Ranking por la clave indicada (categoría, comercio o producto), de mayor a menor. */
    fun ranking(
        movimientos: List<Movimiento>,
        clave: (Movimiento) -> String,
        limite: Int = 5
    ): List<Fila> = movimientos
        .groupBy { clave(it).trim().ifEmpty { "Sin clasificar" } }
        .map { (etiqueta, lista) -> Fila(etiqueta, lista.sumOf { it.importe }, lista.size) }
        .sortedByDescending { it.importe }
        .take(limite)

    /** Comparación de dos periodos: devuelve también la diferencia. */
    data class Comparacion(val actual: Double, val anterior: Double) {
        val diferencia: Double get() = actual - anterior
        val porcentaje: Double? get() = if (anterior == 0.0) null else (actual - anterior) / anterior * 100.0
    }

    fun comparar(actual: Double, anterior: Double): Comparacion = Comparacion(actual, anterior)

    /** El movimiento de mayor importe. */
    fun mayor(movimientos: List<Movimiento>): Movimiento? =
        movimientos.maxByOrNull { it.importe }

    /** El movimiento más reciente. */
    fun ultimo(movimientos: List<Movimiento>): Movimiento? =
        movimientos.maxByOrNull { it.fecha }

    /** Total de una lista. */
    fun total(movimientos: List<Movimiento>): Double = movimientos.sumOf { it.importe }

    /**
     * Frase del ritmo de gasto de un concepto: es la respuesta a «¿cuánto gasto al mes en X?».
     * Ejemplo: total 34,20 € en 12 compras repartidas en 6 meses → 5,70 €/mes.
     */
    fun resumenMensual(movimientos: List<Movimiento>, zona: java.util.TimeZone = java.util.TimeZone.getDefault()): ResumenMensual {
        val meses = porMes(movimientos, zona)
        return ResumenMensual(
            total = total(movimientos),
            compras = movimientos.size,
            mesesConDatos = meses.count { it.movimientos > 0 },
            mediaPorMes = mediaMensual(meses),
            meses = meses
        )
    }

    data class ResumenMensual(
        val total: Double,
        val compras: Int,
        val mesesConDatos: Int,
        val mediaPorMes: Double,
        val meses: List<Fila>
    )
}
