package com.gastos.feature.backup

import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.Product
import com.gastos.repository.ExchangeRateProvider
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object SheetsSchema {
    const val RECIBIDAS = "Facturas Recibidas"
    const val NOMINAS = "Nóminas"
    const val PRODUCTOS = "Productos"
    const val RESUMEN = "Resumen"
    const val SCHEMA_VERSION = 3

    const val RECIBIDAS_KEY_COLUMN = "N"
    const val RECIBIDAS_LAST_COLUMN = "T"
    const val NOMINAS_KEY_COLUMN = "I"
    const val NOMINAS_LAST_COLUMN = "O"
    const val PRODUCTOS_PARENT_COLUMN = "H"
    const val PRODUCTOS_LAST_COLUMN = "P"

    private const val CONVERSION_OK = "OK"
    private const val CONVERSION_LOCAL = "Moneda local"
    private const val CONVERSION_PENDING = "Tasa pendiente"

    val recibidasHeaders: List<Any> = listOf(
        "Nº Factura", "Fecha", "NIF País", "NIF Emisor",
        "Emisor (Razón Social)", "Base Imponible", "Tipo IVA", "Cuota IVA",
        "Recargo Eq.", "IRPF", "Total", "Moneda", "Notas", "ID", "Foto Drive",
        "Total Original", "Moneda Original", "Tasa Aplicada", "Fecha Tasa", "Estado Conversión"
    )

    val nominasHeaders: List<Any> = listOf(
        "Empresa", "Fecha", "Devengado", "Líquido", "IRPF %",
        "Base Cot.", "Seg. Social", "Moneda", "ID",
        "Devengado Original", "Líquido Original", "Moneda Original",
        "Tasa Aplicada", "Fecha Tasa", "Estado Conversión"
    )

    val productosHeaders: List<Any> = listOf(
        "Descripción", "Cantidad", "Precio Unitario", "Subtotal", "IVA %",
        "Total + IVA", "Factura (Proveedor)", "InvoiceID", "ProductID",
        "Precio Unitario Original", "Subtotal Original", "Total + IVA Original",
        "Moneda Original", "Tasa Aplicada", "Fecha Tasa", "Estado Conversión"
    )

    data class ConversionSnapshot(
        val targetCurrency: String,
        private val exchangeRateProvider: ExchangeRateProvider
    ) {
        fun convert(amount: Double, originalCurrency: String): ConvertedAmount {
            val normalizedCurrency = originalCurrency.uppercase()
            val meta = exchangeRateProvider.convertWithMeta(amount, normalizedCurrency, targetCurrency)
            return when {
                meta == null -> ConvertedAmount(
                    convertedAmount = null,
                    originalAmount = round2(amount),
                    originalCurrency = normalizedCurrency,
                    appliedRate = null,
                    rateTimestampLabel = "",
                    status = CONVERSION_PENDING
                )
                meta.wasNative -> ConvertedAmount(
                    convertedAmount = round2(meta.amount),
                    originalAmount = round2(amount),
                    originalCurrency = normalizedCurrency,
                    appliedRate = 1.0,
                    rateTimestampLabel = "",
                    status = CONVERSION_LOCAL
                )
                else -> ConvertedAmount(
                    convertedAmount = round2(meta.amount),
                    originalAmount = round2(amount),
                    originalCurrency = normalizedCurrency,
                    appliedRate = round6(meta.rateApplied),
                    rateTimestampLabel = meta.asOf?.let(::formatTimestamp).orEmpty(),
                    status = CONVERSION_OK
                )
            }
        }
    }

    data class ConvertedAmount(
        val convertedAmount: Double?,
        val originalAmount: Double,
        val originalCurrency: String,
        val appliedRate: Double?,
        val rateTimestampLabel: String,
        val status: String
    )

    fun expenseRow(invoice: Invoice, conversion: ConversionSnapshot): List<Any> {
        val total = conversion.convert(invoice.total, invoice.moneda)
        val convertedTotal = total.convertedAmount
        val convertedBase = convertedTotal?.let {
            if (invoice.ivaPercent > 0) round2(it / (1 + invoice.ivaPercent / 100.0)) else round2(it)
        }
        val convertedQuota = if (convertedTotal != null && convertedBase != null) {
            round2(convertedTotal - convertedBase)
        } else {
            null
        }
        return listOf(
            extractFromOcr(invoice.ocrRawText, "numero_factura"),
            formatDate(invoice.fecha),
            invoice.paisCodigo,
            invoice.nifEmisor ?: "",
            invoice.proveedor,
            convertedBase ?: "",
            invoice.ivaPercent,
            convertedQuota ?: "",
            0.0,
            invoice.irpfPercent,
            convertedTotal ?: "",
            conversion.targetCurrency,
            invoice.notas ?: "",
            invoice.id,
            invoice.driveWebViewLink ?: "",
            total.originalAmount,
            total.originalCurrency,
            total.appliedRate ?: "",
            total.rateTimestampLabel,
            total.status
        )
    }

    fun incomeRow(income: Income, conversion: ConversionSnapshot): List<Any> {
        val originalDevengado = if (income.totalDevengado > 0) income.totalDevengado else income.monto
        val originalLiquido = if (income.totalNeto > 0) income.totalNeto else income.monto
        val devengado = conversion.convert(originalDevengado, income.moneda)
        val liquido = conversion.convert(originalLiquido, income.moneda)
        val primary = if (devengado.status == CONVERSION_PENDING) devengado else liquido
        return listOf(
            income.fuente ?: income.concepto,
            formatDate(income.fecha),
            devengado.convertedAmount ?: "",
            liquido.convertedAmount ?: "",
            income.irpfPercent,
            "",
            "",
            conversion.targetCurrency,
            income.id,
            devengado.originalAmount,
            liquido.originalAmount,
            primary.originalCurrency,
            primary.appliedRate ?: "",
            primary.rateTimestampLabel,
            primary.status
        )
    }

    fun productRow(
        product: Product,
        provider: String,
        originalCurrency: String,
        conversion: ConversionSnapshot
    ): List<Any> {
        val unit = conversion.convert(product.precioUnitario, originalCurrency)
        val subtotal = conversion.convert(product.subtotal, originalCurrency)
        val totalWithVatOriginal = product.subtotal * (1 + product.ivaPercent / 100.0)
        val totalWithVat = conversion.convert(totalWithVatOriginal, originalCurrency)
        val primary = if (subtotal.status == CONVERSION_PENDING) subtotal else totalWithVat
        return listOf(
            product.descripcion,
            product.cantidad,
            unit.convertedAmount ?: "",
            subtotal.convertedAmount ?: "",
            product.ivaPercent,
            totalWithVat.convertedAmount ?: "",
            provider,
            product.invoiceId,
            product.id,
            unit.originalAmount,
            subtotal.originalAmount,
            totalWithVat.originalAmount,
            primary.originalCurrency,
            primary.appliedRate ?: "",
            primary.rateTimestampLabel,
            primary.status
        )
    }

    private fun formatDate(timestamp: Long): String =
        SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(timestamp))

    private fun formatTimestamp(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

    private fun extractFromOcr(rawText: String?, field: String): String {
        if (rawText.isNullOrBlank()) return ""
        return runCatching {
            val json = Regex("""\{[\s\S]*\}""").find(rawText)?.value ?: return ""
            JSONObject(json).optString(field, "").ifBlank { "" }
        }.getOrDefault("")
    }

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0

    private fun round6(value: Double): Double = Math.round(value * 1_000_000.0) / 1_000_000.0
}
