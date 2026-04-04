package com.gastos.feature.ai

import com.gastos.domain.model.Income
import org.json.JSONObject

object IncomeQueryResultParser {
    const val PREFIX_RECORD = "INCOME_RECORD:"

    fun parse(queryResult: String): Income? {
        val qr = queryResult.trim()
        if (qr.startsWith(PREFIX_RECORD)) {
            val j = JSONObject(qr.removePrefix(PREFIX_RECORD))
            val monto = j.optDouble("monto", 0.0)
            val td = j.optDouble("total_devengado", 0.0)
            val tn = j.optDouble("total_neto", 0.0)
            val tded = j.optDouble("total_deducciones", 0.0)
            val tipoIng = j.optString("tipo_ingreso", "").takeIf { it.isNotEmpty() }
            val netoEff = if (tn > 0) tn else monto
            val devEff = if (td > 0) td else monto
            val dedEff = if (tded > 0) tded else (devEff - netoEff).takeIf { it > 0.01 } ?: 0.0
            return Income(
                fecha = j.optLong("fecha", System.currentTimeMillis()),
                concepto = j.optString("concepto", ""),
                monto = monto,
                totalDevengado = devEff,
                totalDeducciones = dedEff,
                totalNeto = if (tn > 0) tn else monto,
                moneda = j.optString("moneda", "EUR").ifBlank { "EUR" },
                tipoIngreso = tipoIng,
                fuente = if (j.isNull("fuente")) null else j.optString("fuente").takeIf { it.isNotEmpty() },
                categoria = if (j.isNull("categoria")) null else j.optString("categoria").takeIf { it.isNotEmpty() }
            )
        }
        if (qr.startsWith("INCOME:")) {
            val parts = qr.split(":")
            if (parts.size < 4) return null
            val concepto = parts[1]
            val monto = parts[2].toDoubleOrNull() ?: 0.0
            val moneda = parts.getOrNull(3) ?: "EUR"
            val fecha = parts.getOrNull(4)?.toLongOrNull() ?: System.currentTimeMillis()
            val fuente = parts.getOrNull(5)?.takeIf { it != "null" && it.isNotEmpty() }
            val categoria = parts.getOrNull(6)?.takeIf { it != "null" && it.isNotEmpty() }
            return Income(
                fecha = fecha,
                concepto = concepto,
                monto = monto,
                moneda = moneda,
                fuente = fuente,
                categoria = categoria
            )
        }
        return null
    }
}
