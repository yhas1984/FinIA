package com.gastos.feature.ai

import org.json.JSONObject

/**
 * Revisa la consulta financiera que devuelve el modelo antes de ejecutarla.
 *
 * El objetivo es que nunca se ejecute una consulta mal formada: si algo no cuadra
 * (una fecha imposible, un periodo que no se entiende, un modo de coincidencia raro),
 * se le devuelve el error al modelo para que la corrija y, si no lo consigue, la capa
 * local resuelve el periodo a partir de lo que escribió el usuario.
 */
internal object QuerySpecValidator {

    private val TIPOS = setOf("gastos", "ingresos", "balance", "productos", "productos_por_comercio")
    private val PALABRAS_PERIODO = setOf(
        "hoy", "today", "semana", "esta semana", "semana actual", "lo que va de semana",
        "week", "this week", "mes", "este mes", "mes actual", "lo que va de mes", "month",
        "this month", "año", "ano", "este año", "este ano", "year", "this year",
        "mes anterior", "el mes pasado", "mes pasado", "last month",
        "todo", "todo el histórico", "historico", "histórico", "en total", "todos los periodos", "all time"
    )
    private val OPERACIONES = setOf("total", "resumen_mensual", "ranking", "comparar", "mayor", "ultimo")
    private val AGRUPACIONES = setOf("mes", "categoria", "comercio", "producto")
    private val FECHA_ISO = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val MES_ISO = Regex("""^\d{4}-\d{1,2}$""")
    private val RANGO_ISO = Regex("""^\d{4}-\d{2}-\d{2}\.\.\d{4}-\d{2}-\d{2}$""")

    /** Lista de fallos encontrados; vacía significa que la consulta se puede ejecutar. */
    fun validate(texto: String, hoy: String = ""): List<String> {
        val limpio = texto.trim()
        // Una respuesta conversacional (no es JSON) es válida: no todo es una consulta.
        if (!limpio.startsWith("{")) return emptyList()
        val json = try {
            JSONObject(limpio)
        } catch (_: Exception) {
            return listOf("el JSON no se puede leer")
        }
        if (json.optString("action") != "query") return emptyList()

        val errores = mutableListOf<String>()
        val tipo = json.optString("query_type", json.optString("queryType", ""))
        if (tipo.isNotEmpty() && tipo !in TIPOS) errores.add("query_type desconocido: $tipo")

        fun fecha(campo: String): String? {
            val valor = json.optString(campo, "").trim()
            if (valor.isEmpty() || valor == "null") return null
            if (!FECHA_ISO.matches(valor)) {
                errores.add("$campo no es una fecha YYYY-MM-DD: $valor")
                return null
            }
            return valor
        }

        val desde = fecha("desde")
        val hasta = fecha("hasta")
        if (desde != null && hasta != null && desde > hasta) errores.add("desde es posterior a hasta")

        val periodo = json.optString("periodo", json.optString("period", "")).trim()
        if (periodo.isNotEmpty() && periodo != "null" && desde == null && hasta == null) {
            val conocido = periodo.lowercase() in PALABRAS_PERIODO ||
                MES_ISO.matches(periodo) || RANGO_ISO.matches(periodo)
            if (!conocido) {
                errores.add(
                    "periodo no reconocido: $periodo (usa YYYY-MM, un rango " +
                        "YYYY-MM-DD..YYYY-MM-DD, uno de hoy|semana|mes|año|mes anterior, o desde/hasta)"
                )
            }
        }

        val operacion = json.optString("operacion", "").trim()
        if (operacion.isNotEmpty() && operacion != "null" && operacion !in OPERACIONES) {
            errores.add("operacion no reconocida: $operacion")
        }
        val agrupacion = json.optString("agrupar_por", "").trim()
        if (agrupacion.isNotEmpty() && agrupacion != "null" && agrupacion !in AGRUPACIONES) {
            errores.add("agrupar_por no reconocido: $agrupacion")
        }
        val modo = json.optString("match_mode", "").trim()
        if (modo.isNotEmpty() && modo != "null" && modo !in setOf("exact", "group", "auto")) {
            errores.add("match_mode desconocido: $modo")
        }
        return errores
    }
}
