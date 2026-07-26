package com.gastos.feature.backup

import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.Product
import com.gastos.domain.model.TransactionCategories
import com.gastos.repository.ExchangeRateProvider
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object SheetsSchema {
    const val RECIBIDAS = "Facturas Recibidas"
    const val NOMINAS = "Nóminas"
    const val INGRESOS = "Ingresos"
    const val PRODUCTOS = "Productos"
    const val RESUMEN = "Resumen"
    const val SCHEMA_VERSION = 6

    const val RECIBIDAS_KEY_COLUMN = "O"
    const val RECIBIDAS_LAST_COLUMN = "U"
    const val NOMINAS_KEY_COLUMN = "J"
    const val NOMINAS_LAST_COLUMN = "P"
    const val INGRESOS_KEY_COLUMN = "H"
    const val INGRESOS_LAST_COLUMN = "M"
    const val PRODUCTOS_PARENT_COLUMN = "H"
    const val PRODUCTOS_LAST_COLUMN = "P"

    private const val CONVERSION_OK = "OK"
    private const val CONVERSION_LOCAL = "Moneda local"
    private const val CONVERSION_PENDING = "Tasa pendiente"

    val recibidasHeaders: List<Any> = listOf(
        "Nº Factura", "Fecha", "NIF País", "NIF Emisor",
        "Emisor (Razón Social)", "Base Imponible", "Tipo IVA", "Cuota IVA",
        "Recargo Eq.", "IRPF %", "Total antes de retención", "Moneda", "Categoría", "Notas", "ID", "Foto Drive",
        "Total Original", "Moneda Original", "Tasa Aplicada", "Fecha Tasa", "Estado Conversión"
    )

    val nominasHeaders: List<Any> = listOf(
        "Empresa", "Fecha", "Devengado", "Líquido", "IRPF %",
        "Base Cot.", "Seg. Social", "Moneda", "Categoría", "ID",
        "Devengado Original", "Líquido Original", "Moneda Original",
        "Tasa Aplicada", "Fecha Tasa", "Estado Conversión"
    )

    val productosHeaders: List<Any> = listOf(
        "Descripción", "Cantidad", "Precio Unitario", "Subtotal", "IVA %",
        "Total (IVA incluido)", "Factura (Proveedor)", "InvoiceID", "ProductID",
        "Precio Unitario Original", "Subtotal Original", "Total Original (IVA incluido)",
        "Moneda Original", "Tasa Aplicada", "Fecha Tasa", "Estado Conversión"
    )

    val ingresosHeaders: List<Any> = listOf(
        "Concepto", "Fecha", "Importe", "Moneda", "Fuente", "Categoría", "Notas", "ID",
        "Importe Original", "Moneda Original", "Tasa Aplicada", "Fecha Tasa", "Estado Conversión"
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

    data class SummaryTotals(
        val totalExpenses: Double,
        val totalIncomes: Double,
        val balance: Double,
        val pendingConversions: Int
    )

    fun summaryTotals(
        invoices: List<Invoice>,
        incomes: List<Income>,
        conversion: ConversionSnapshot
    ): SummaryTotals {
        val expenseAmounts = invoices
            .filter { it.tipo == com.gastos.domain.model.InvoiceType.GASTO }
            .map { conversion.convert(it.total, it.moneda) }
        val invoiceIncomeAmounts = invoices
            .filter { it.tipo == com.gastos.domain.model.InvoiceType.INGRESO }
            .map { conversion.convert(it.total, it.moneda) }
        val incomeAmounts = incomes.map { conversion.convert(it.monto, it.moneda) }
        val totalExpenses = expenseAmounts.sumOf { it.convertedAmount ?: 0.0 }
        val totalIncomes = (invoiceIncomeAmounts + incomeAmounts)
            .sumOf { it.convertedAmount ?: 0.0 }
        val pending = (expenseAmounts + invoiceIncomeAmounts + incomeAmounts)
            .count { it.convertedAmount == null }
        return SummaryTotals(
            totalExpenses = round2(totalExpenses),
            totalIncomes = round2(totalIncomes),
            balance = round2(totalIncomes - totalExpenses),
            pendingConversions = pending
        )
    }

    fun summaryRows(
        exportDate: String,
        reportCurrency: String,
        totals: SummaryTotals
    ): List<List<Any>> = listOf(
        listOf("Resumen Financiero (AEAT)"),
        listOf("Fecha actualización", exportDate),
        listOf("Moneda informe", reportCurrency),
        listOf("Total Gastos", totals.totalExpenses),
        listOf("Total Ingresos", totals.totalIncomes),
        listOf("Balance", totals.balance),
        listOf("Conversiones pendientes", totals.pendingConversions)
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
            TransactionCategories.displayCategory(invoice.categoria),
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
            TransactionCategories.displayCategory(income.categoria),
            income.id,
            devengado.originalAmount,
            liquido.originalAmount,
            primary.originalCurrency,
            primary.appliedRate ?: "",
            primary.rateTimestampLabel,
            primary.status
        )
    }

    fun isPayrollIncome(income: Income): Boolean =
        TransactionCategories.matchesCategory(income.categoria, "Nómina")

    fun genericIncomeRow(income: Income, conversion: ConversionSnapshot): List<Any> {
        val amount = conversion.convert(income.monto, income.moneda)
        return listOf(
            income.concepto,
            formatDate(income.fecha),
            amount.convertedAmount ?: "",
            conversion.targetCurrency,
            income.fuente ?: "",
            TransactionCategories.displayCategory(income.categoria),
            income.notas ?: "",
            income.id,
            amount.originalAmount,
            amount.originalCurrency,
            amount.appliedRate ?: "",
            amount.rateTimestampLabel,
            amount.status
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
        // El OCR y el modelo de dominio definen precio/subtotal como importes
        // finales con IVA incluido. No se vuelve a sumar aquí.
        val totalWithVatOriginal = product.subtotal
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
