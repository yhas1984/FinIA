package com.gastos.feature.backup

import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.Product
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object SheetsSchema {
    const val RECIBIDAS = "Facturas Recibidas"
    const val NOMINAS = "Nóminas"
    const val PRODUCTOS = "Productos"
    const val RESUMEN = "Resumen"

    const val RECIBIDAS_KEY_COLUMN = "N"
    const val RECIBIDAS_LAST_COLUMN = "O"
    const val NOMINAS_KEY_COLUMN = "I"
    const val NOMINAS_LAST_COLUMN = "I"
    const val PRODUCTOS_PARENT_COLUMN = "H"
    const val PRODUCTOS_LAST_COLUMN = "I"

    val recibidasHeaders: List<Any> = listOf(
        "Nº Factura", "Fecha", "NIF País", "NIF Emisor",
        "Emisor (Razón Social)", "Base Imponible", "Tipo IVA", "Cuota IVA",
        "Recargo Eq.", "IRPF", "Total", "Moneda", "Notas", "ID", "Foto Drive"
    )

    val nominasHeaders: List<Any> = listOf(
        "Empresa", "Fecha", "Devengado", "Líquido", "IRPF %",
        "Base Cot.", "Seg. Social", "Moneda", "ID"
    )

    val productosHeaders: List<Any> = listOf(
        "Descripción", "Cantidad", "Precio Unitario", "Subtotal", "IVA %",
        "Total + IVA", "Factura (Proveedor)", "InvoiceID", "ProductID"
    )

    fun expenseRow(invoice: Invoice): List<Any> {
        val base = if (invoice.ivaPercent > 0) {
            invoice.total / (1 + invoice.ivaPercent / 100.0)
        } else {
            invoice.total
        }
        return listOf(
            extractFromOcr(invoice.ocrRawText, "numero_factura"),
            formatDate(invoice.fecha),
            invoice.paisCodigo,
            invoice.nifEmisor ?: "",
            invoice.proveedor,
            round2(base),
            invoice.ivaPercent,
            round2(invoice.total - base),
            0.0,
            invoice.irpfPercent,
            invoice.total,
            invoice.moneda,
            invoice.notas ?: "",
            invoice.id,
            invoice.driveWebViewLink ?: ""
        )
    }

    fun incomeRow(income: Income): List<Any> = listOf(
        income.fuente ?: income.concepto,
        formatDate(income.fecha),
        income.totalDevengado,
        income.totalNeto,
        income.irpfPercent,
        "",
        "",
        income.moneda,
        income.id
    )

    fun productRow(product: Product, provider: String): List<Any> = listOf(
        product.descripcion,
        product.cantidad,
        product.precioUnitario,
        product.subtotal,
        product.ivaPercent,
        round2(product.subtotal * (1 + product.ivaPercent / 100.0)),
        provider,
        product.invoiceId,
        product.id
    )

    private fun formatDate(timestamp: Long): String =
        SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(timestamp))

    private fun extractFromOcr(rawText: String?, field: String): String {
        if (rawText.isNullOrBlank()) return ""
        return runCatching {
            val json = Regex("""\{[\s\S]*\}""").find(rawText)?.value ?: return ""
            JSONObject(json).optString(field, "").ifBlank { "" }
        }.getOrDefault("")
    }

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0
}
