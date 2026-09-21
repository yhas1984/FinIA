package com.gastos.feature.ai

import com.gastos.domain.model.DocumentValidator
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.Product
import com.gastos.domain.model.TransactionCategories
import org.json.JSONObject
import kotlin.math.abs

/** Commands contain observations, not a license to fill missing financial fields. */
internal object CommandDocumentParser {
    /** Check explicit numeric dates in the user's text before trusting the generated command. */
    fun preserveExplicitDate(json: JSONObject, original: String, locale: java.util.Locale = java.util.Locale.getDefault()) {
        val dates: List<String> = Regex("(?<![\\d/.-])(?:\\d{4}-\\d{1,2}-\\d{1,2}|\\d{1,2}[/.]\\d{1,2}[/.]\\d{4})(?![\\d/.-])")
            .findAll(original).map { match ->
                val value: String = match.value
                val parts: List<String> = value.split('-', '/', '.')
                val iso: String = if (value.contains('-')) "%04d-%02d-%02d".format(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
                    else if (locale.country == "US") "%04d-%02d-%02d".format(parts[2].toInt(), parts[0].toInt(), parts[1].toInt())
                    else "%04d-%02d-%02d".format(parts[2].toInt(), parts[1].toInt(), parts[0].toInt())
                date(iso)
                iso
            }.toList().distinct()
        require(dates.size <= 1) { "Specify one transaction date" }
        dates.singleOrNull()?.let { json.put("fecha", it) }
    }
    fun date(value: String?, now: Long = System.currentTimeMillis()): Long =
        if (value.isNullOrBlank()) now else requireNotNull(DocumentValidator.parseDate(value)) { "Invalid date: yyyy-MM-dd required" }

    fun expense(json: JSONObject, currency: String): Pair<Invoice, List<Product>> {
        val description: String = text(json, "descripcion") ?: text(json, "concepto").orEmpty()
        val amount: Double = requireNotNull(number(json, "total") ?: number(json, "monto")) { "Missing amount" }
        require(description.isNotEmpty() && amount > 0) { "Invalid expense" }
        val rate: Double? = percent(json, "tipo_iva") ?: percent(json, "iva_percent")
        val invoice: Invoice = Invoice(fecha = date(text(json, "fecha")), proveedor = description,
            tipo = InvoiceType.GASTO, total = amount, moneda = currency, ivaPercent = rate,
            baseImponible = number(json, "base_imponible"), cuotaIva = number(json, "cuota_iva"),
            irpfPercent = percent(json, "retencion_irpf") ?: 0.0,
            numeroFactura = text(json, "numero_factura"), taxes = DocumentReader.commandTaxes(json, currency),
            categoria = TransactionCategories.canonicalExpenseCategory(text(json, "categoria")),
            subcategoria = TransactionCategories.normalizeCategory(text(json, "subcategoria")))
        val rows: org.json.JSONArray? = json.optJSONArray("productos")
        require(!json.has("productos") || json.isNull("productos") || rows != null) { "Invalid products" }
        val products: List<Product> = if (rows == null) emptyList() else (0 until rows.length()).map { index ->
            val row: JSONObject = rows.getJSONObject(index)
            val quantity: Double = requireNotNull(number(row, "cantidad")) { "Missing quantity" }
            val price: Double = requireNotNull(number(row, "precio_unitario")) { "Missing unit price" }
            val subtotal: Double = number(row, "subtotal") ?: quantity * price
            val name: String = text(row, "descripcion").orEmpty()
            require(name.isNotEmpty() && quantity > 0 && price >= 0 && subtotal.isFinite() &&
                abs(quantity * price - subtotal) <= 0.02) { "Inconsistent product" }
            Product(invoiceId = 0, descripcion = name, cantidad = quantity, precioUnitario = price,
                subtotal = subtotal, ivaPercent = percent(row, "iva_percent"), taxes = DocumentReader.commandTaxes(row, currency))
        }
        require(products.isEmpty() || abs(products.sumOf { it.subtotal } - amount) <= 0.02) { "Products do not match total" }
        return invoice to products
    }

    fun income(json: JSONObject, currency: String): Income {
        val description: String = text(json, "concepto") ?: text(json, "descripcion").orEmpty()
        val gross: Double? = number(json, "total_devengado")
        val net: Double? = number(json, "total_neto")
        val amount: Double = requireNotNull(number(json, "monto") ?: net) { "Missing received amount" }
        require(description.isNotEmpty() && amount > 0 && (gross == null || gross >= 0) && (net == null || net >= 0)) { "Invalid income" }
        require(net == null || abs(net - amount) <= 0.02) { "Net does not match received amount" }
        return Income(fecha = date(text(json, "fecha")), concepto = description, monto = amount,
            totalDevengado = gross ?: 0.0, totalNeto = net ?: 0.0, moneda = currency,
            irpfPercent = percent(json, "retencion_irpf") ?: 0.0,
            ivaPercent = percent(json, "tipo_iva") ?: percent(json, "iva_percent"),
            fuente = text(json, "fuente"), taxes = DocumentReader.commandTaxes(json, currency),
            categoria = TransactionCategories.canonicalIncomeCategory(text(json, "categoria")),
            subcategoria = TransactionCategories.normalizeCategory(text(json, "subcategoria")))
    }

    private fun text(json: JSONObject, key: String): String? =
        if (json.isNull(key)) null else json.optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }

    private fun percent(json: JSONObject, key: String): Double? = number(json, key)?.also {
        require(it in 0.0..100.0) { "Invalid percentage: $key" }
    }

    private fun number(json: JSONObject, key: String): Double? {
        if (!json.has(key) || json.isNull(key)) return null
        val raw: String = json.get(key).toString().trim()
        require(Regex("-?[0-9]+([.,][0-9]+)?").matches(raw)) { "Invalid number: $key" }
        return requireNotNull(raw.replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite)) { "Invalid number: $key" }
    }
}
