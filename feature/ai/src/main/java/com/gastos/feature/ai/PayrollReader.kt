package com.gastos.feature.ai

import com.gastos.domain.model.*
import org.json.JSONArray
import org.json.JSONObject

internal object PayrollReader {
    fun schema(): JSONObject = JSONObject().put("type", "OBJECT").put("nullable", true).apply {
        val properties: JSONObject = JSONObject()
        listOf("fecha_pago", "texto_fecha_pago", "fecha_emision", "texto_fecha_emision", "periodo_inicio", "periodo_fin").forEach {
            properties.put(it, JSONObject().put("type", "STRING").put("nullable", true))
        }
        listOf("total_deducciones").forEach {
            properties.put(it, JSONObject().put("type", "NUMBER").put("nullable", true))
        }
        properties.put("conceptos_completos", JSONObject().put("type", "BOOLEAN"))
        val line: JSONObject = JSONObject().put("descripcion", JSONObject().put("type", "STRING").put("nullable", true))
        line.put("tipo", JSONObject().put("type", "STRING").put("enum", JSONArray(PayrollLineType.entries.map { it.name })))
        line.put("tipo_deduccion", JSONObject().put("type", "STRING").put("enum", JSONArray(PayrollDeductionType.entries.map { it.name })))
        listOf("importe", "unidades", "valor_base").forEach { line.put(it, JSONObject().put("type", "NUMBER").put("nullable", true)) }
        properties.put("conceptos", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "OBJECT")
            .put("properties", line).put("required", JSONArray(line.keys().asSequence().toList()))))
        put("properties", properties).put("required", JSONArray(properties.keys().asSequence().toList()))
    }

    fun read(json: JSONObject, invalid: MutableSet<String>): PayrollDetails? {
        val source: JSONObject = json.optJSONObject("detalle_nomina") ?: run { invalid.add("payroll"); return null }
        fun text(key: String): String? = if (source.isNull(key)) null else source.optString(key).trim().takeIf { it.isNotBlank() }
        fun number(row: JSONObject, key: String, field: String): Double? {
            if (row.isNull(key)) return null
            val value: Any? = row.opt(key)
            val parsed: Double? = when (value) {
                is Number -> value.toDouble()
                is String -> value.takeIf { Regex("-?\\d+(?:[.,]\\d+)?").matches(it) }?.replace(',', '.')?.toDoubleOrNull()
                else -> null
            }?.takeIf(Double::isFinite)
            if (parsed == null) invalid.add(field)
            return parsed
        }
        val rows: JSONArray? = source.optJSONArray("conceptos")
        if (rows == null) invalid.add("payroll.lines")
        val lines: List<PayrollLine> = (0 until (rows?.length() ?: 0)).map { index ->
            val row: JSONObject = rows?.optJSONObject(index) ?: JSONObject().also { invalid.add("payroll.lines.$index") }
            val type: PayrollLineType = PayrollLineType.entries.firstOrNull { it.name == row.optString("tipo") } ?: PayrollLineType.UNKNOWN
            val deduction: PayrollDeductionType = PayrollDeductionType.entries.firstOrNull { it.name == row.optString("tipo_deduccion") } ?: PayrollDeductionType.UNKNOWN
            PayrollLine(description = if (row.isNull("descripcion")) null else row.optString("descripcion"), type = type,
                amount = number(row, "importe", "payroll.lines.$index.amount"), quantity = number(row, "unidades", "payroll.lines.$index.quantity"),
                unitRate = number(row, "valor_base", "payroll.lines.$index.unitRate"), deductionType = deduction)
        }
        return PayrollDetails(paymentDate = text("fecha_pago"), paymentDateText = text("texto_fecha_pago"),
            issueDate = text("fecha_emision"), issueDateText = text("texto_fecha_emision"), periodStart = text("periodo_inicio"), periodEnd = text("periodo_fin"),
            totalDeductions = number(source, "total_deducciones", "payroll.totalDeductions"),
            lines = lines,
            linesComplete = (source.opt("conceptos_completos") as? Boolean))
    }
}
