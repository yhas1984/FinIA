package com.gastos.feature.ai

import com.gastos.domain.model.*
import org.json.JSONObject

sealed interface DocumentReadResult {
    data class Ready(val evidence: DocumentEvidence) : DocumentReadResult
    data class NeedsReview(val evidence: DocumentEvidence, val issues: List<ReviewIssue>) : DocumentReadResult
    data class Duplicate(val matches: List<DuplicateMatch>) : DocumentReadResult
    data class Failure(val message: String) : DocumentReadResult
}

/** Preserve the extraction and accept partial details without an accounting review gate. */
internal object DocumentReader {
    fun commandTaxes(json: JSONObject, currency: String): List<DocumentTax> {
        val invalid: MutableSet<String> = mutableSetOf()
        validateTaxInput(json, "taxes", invalid, mutableMapOf())
        val taxes: List<DocumentTax> = readTaxes(json)
        require(invalid.isEmpty() && DocumentTaxes.validate(taxes, currency).isEmpty()) { "Invalid tax breakdown" }
        return taxes
    }
    fun parse(response: String, defaultCurrency: String = "EUR"): DocumentReadResult {
        val raw: String = Regex("\\{[\\s\\S]*\\}").find(response)?.value ?: response
        val json: JSONObject = JSONObject(raw)
        fun text(key: String): String? = readText(json, key)
        fun number(key: String): Double? = readNumber(json, key)
        val invalid: MutableSet<String> = mutableSetOf()
        val payroll: PayrollDetails? = if (text("tipo_documento") == "nomina") PayrollReader.read(json, invalid) else null
        val products: org.json.JSONArray? = json.optJSONArray("productos")
        val lines: List<ScannedLine> = (0 until (products?.length() ?: 0)).map { index ->
            val line: JSONObject = products?.optJSONObject(index) ?: JSONObject()
            ScannedLine(readText(line, "descripcion"), readNumber(line, "cantidad"),
                readNumber(line, "precio_unitario"), readNumber(line, "subtotal"), readNumber(line, "iva_percent"), readTaxes(line))
        }
        val document: ScannedDocument = ScannedDocument(
            taxes = readTaxes(json),
            taxesComplete = if (json.has("impuestos_completos") && !json.isNull("impuestos_completos")) json.optBoolean("impuestos_completos") else null,
            kind = text("tipo_documento"), country = text("pais"), currency = text("moneda")?.uppercase(java.util.Locale.ROOT),
            date = text("fecha"), number = text("numero_factura"), issuer = if (text("tipo_documento") == "nomina") text("empresa") ?: text("proveedor") else text("proveedor") ?: text("empresa"),
            issuerTaxId = text("nif_emisor"), recipientTaxId = text("nif_receptor"), category = text("categoria"), subcategory = text("subcategoria"),
            total = number("total"), taxBase = number("base_imponible"), vatAmount = number("cuota_iva"), vatPercent = number("tipo_iva"),
            withholdingPercent = number("retencion_irpf"), withholdingAmount = number("importe_retencion"), discount = number("descuento"),
            priceBasis = text("precios_impuestos"), gross = number("devengado"), net = number("liquido"), contributionBase = number("base_cotizacion"),
            // The legacy scalar has no column provenance. With typed payroll evidence,
            // only the verified payment deductions can supply this aggregate.
            socialSecurity = if (payroll != null) null else number("seguridad_social"), payrollReference = text("referencia_nomina"), workerId = text("identificador_trabajador"),
            payPeriod = text("periodo_liquidacion"), paymentKind = text("tipo_pago"), lines = lines,
            linesComplete = if (json.has("productos_completos") && !json.isNull("productos_completos")) json.optBoolean("productos_completos") else null,
            payroll = payroll)
        val originals: MutableMap<String, String> = mutableMapOf()
        validateTaxInput(json, "taxes", invalid, originals)
        val numericKeys: Map<String, String> = mapOf("total" to "total", "base_imponible" to "taxBase", "cuota_iva" to "vatAmount",
            "tipo_iva" to "vatPercent", "retencion_irpf" to "withholdingPercent", "importe_retencion" to "withholdingAmount",
            "descuento" to "discount", "devengado" to "gross", "liquido" to "net", "base_cotizacion" to "contributionBase", "seguridad_social" to "socialSecurity")
        numericKeys.forEach { (key, field) ->
            if (!json.isNull(key) && readNumber(json, key) == null) { invalid.add(field); originals[field] = json.opt(key).toString() }
        }
        (0 until (products?.length() ?: 0)).forEach { index ->
            val line: JSONObject = products?.optJSONObject(index) ?: JSONObject()
            validateTaxInput(line, "lines.$index.taxes", invalid, originals)
            mapOf("cantidad" to "quantity", "precio_unitario" to "unitPrice", "subtotal" to "subtotal", "iva_percent" to "vatPercent").forEach { (key, name) ->
                if (!line.isNull(key) && readNumber(line, key) == null) {
                    val field: String = "lines.$index.$name"
                    invalid.add(field); originals[field] = line.opt(key).toString()
                }
            }
        }
        val source: DocumentEvidence = DocumentEvidence(document, originalExtraction = raw, invalidFields = invalid, fieldEdits = originals)
        val prepared: DocumentEvidence = DocumentExtraction.prepare(source)
        if (DocumentExtraction.amount(prepared.document) == null) throw UnreadableDocumentAmountException()
        val evidence: DocumentEvidence = if (prepared.document.currency == null) prepared.copy(
            document = prepared.document.copy(currency = defaultCurrency), derivedFields = prepared.derivedFields + "currency") else prepared
        return DocumentReadResult.Ready(evidence)
    }

    private fun readTaxes(json: JSONObject): List<DocumentTax> {
        val rows: org.json.JSONArray = json.optJSONArray("impuestos") ?: return emptyList()
        return (0 until rows.length()).map { index ->
            val row: JSONObject = rows.optJSONObject(index) ?: JSONObject()
            val rate: Double? = readNumber(row, "porcentaje")
            val treatment: TaxTreatment = readText(row, "tratamiento")?.let { value ->
                TaxTreatment.entries.firstOrNull { it.name == value }
            } ?: TaxTreatment.UNKNOWN
            DocumentTax(name = readText(row, "nombre"), rate = rate, base = readNumber(row, "base"),
                amount = readNumber(row, "importe"), treatment = treatment,
                effect = if (readText(row, "efecto") == "WITHHOLDING") TaxEffect.WITHHOLDING else TaxEffect.CHARGE)
        }
    }

    private fun validateTaxInput(json: JSONObject, prefix: String, invalid: MutableSet<String>, originals: MutableMap<String, String>) {
        if (!json.has("impuestos") || json.isNull("impuestos")) return
        val rows: org.json.JSONArray? = json.optJSONArray("impuestos")
        if (rows == null) { invalid.add(prefix); return }
        (0 until rows.length()).forEach { index ->
            val row: JSONObject? = rows.optJSONObject(index)
            if (row == null) { invalid.add("$prefix.$index"); return@forEach }
            mapOf("porcentaje" to "rate", "base" to "base", "importe" to "amount").forEach { (key, name) ->
                if (!row.isNull(key) && readNumber(row, key) == null) {
                    invalid.add("$prefix.$index.$name"); originals["$prefix.$index.$name"] = row.opt(key).toString()
                }
            }
            if (readText(row, "tratamiento") !in TaxTreatment.entries.map { it.name } + null) invalid.add("$prefix.$index.treatment")
            if (readText(row, "efecto") !in TaxEffect.entries.map { it.name }) invalid.add("$prefix.$index.effect")
        }
    }

    private fun readText(json: JSONObject, key: String): String? =
        if (json.isNull(key)) null else json.optString(key).trim().takeIf {
            it.isNotEmpty() && it.lowercase(java.util.Locale.ROOT) !in setOf("null", "unknown", "desconocido", "n/a", "-")
        }

    private fun readNumber(json: JSONObject, key: String): Double? {
        if (json.isNull(key)) return null
        val value: Any? = json.opt(key)
        // The schema requests JSON numbers. Strings are accepted only in unambiguous decimal notation.
        return when (value) {
            is Number -> value.toDouble().takeIf(Double::isFinite)
            is String -> value.trim().takeIf { Regex("-?\\d+(?:[.,]\\d+)?").matches(it) }?.replace(',', '.')?.toDoubleOrNull()?.takeIf(Double::isFinite)
            else -> null
        }
    }
}
