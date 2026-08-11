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
    const val SCHEMA_VERSION = 7
    const val SCHEMA_LOCALE_ES = "es"
    const val SCHEMA_LOCALE_EN = "en"
    const val LEGACY_NOMINAS = "Nóminas"
    private const val CONVERSION_OK = "OK"

    enum class LocaleCode(val code: String) { ES(SCHEMA_LOCALE_ES), EN(SCHEMA_LOCALE_EN) }

    data class Descriptor(
        val locale: LocaleCode,
        val recibidasTitle: String,
        val ingresosTitle: String,
        val productosTitle: String,
        val resumenTitle: String,
        val recibidasHeaders: List<Any>,
        val productosHeaders: List<Any>,
        val ingresosHeaders: List<Any>,
        val summaryTitle: String,
        val summaryUpdatedLabel: String,
        val summaryCurrencyLabel: String,
        val summaryExpensesLabel: String,
        val summaryIncomeLabel: String,
        val summaryBalanceLabel: String,
        val summaryPendingLabel: String,
        val conversionOkLabel: String,
        val conversionLocalLabel: String,
        val conversionPendingLabel: String,
    )

    val es = Descriptor(
        locale = LocaleCode.ES,
        recibidasTitle = "Facturas Recibidas",
        ingresosTitle = "Ingresos",
        productosTitle = "Productos",
        resumenTitle = "Resumen",
        recibidasHeaders = listOf("Nº Factura", "Fecha", "NIF País", "NIF Emisor", "Emisor (Razón Social)", "Base Imponible", "Tipo IVA", "Cuota IVA", "Recargo Eq.", "IRPF %", "Total antes de retención", "Moneda", "Categoría", "Notas", "ID", "Foto Drive", "Total Original", "Moneda Original", "Tasa Aplicada", "Fecha Tasa", "Estado Conversión"),
        productosHeaders = listOf("Descripción", "Cantidad", "Precio Unitario", "Subtotal", "IVA %", "Total (IVA incluido)", "Factura (Proveedor)", "InvoiceID", "ProductID", "Precio Unitario Original", "Subtotal Original", "Total Original (IVA incluido)", "Moneda Original", "Tasa Aplicada", "Fecha Tasa", "Estado Conversión"),
        ingresosHeaders = listOf("Concepto", "Fecha", "Importe", "Devengado", "Líquido", "IRPF %", "Moneda", "Fuente", "Categoría", "Notas", "ID", "Importe Original", "Devengado Original", "Líquido Original", "Moneda Original", "Tasa Aplicada", "Fecha Tasa", "Estado Conversión"),
        summaryTitle = "Resumen Financiero (AEAT)", summaryUpdatedLabel = "Fecha actualización", summaryCurrencyLabel = "Moneda informe", summaryExpensesLabel = "Total Gastos", summaryIncomeLabel = "Total Ingresos", summaryBalanceLabel = "Balance", summaryPendingLabel = "Conversiones pendientes", conversionOkLabel = "OK", conversionLocalLabel = "Moneda local", conversionPendingLabel = "Tasa pendiente"
    )
    val en = Descriptor(
        locale = LocaleCode.EN,
        recibidasTitle = "Received Invoices",
        ingresosTitle = "Income",
        productosTitle = "Products",
        resumenTitle = "Summary",
        recibidasHeaders = listOf("Invoice No.", "Date", "Country VAT ID", "Issuer VAT ID", "Issuer (Legal Name)", "Tax Base", "VAT %", "VAT Amount", "Surcharge", "Withholding %", "Total before withholding", "Currency", "Category", "Notes", "ID", "Drive Photo", "Original Total", "Original Currency", "Applied Rate", "Rate Date", "Conversion Status"),
        productosHeaders = listOf("Description", "Quantity", "Unit Price", "Subtotal", "VAT %", "Total (VAT included)", "Invoice (Supplier)", "InvoiceID", "ProductID", "Original Unit Price", "Original Subtotal", "Original Total (VAT included)", "Original Currency", "Applied Rate", "Rate Date", "Conversion Status"),
        ingresosHeaders = listOf("Concept", "Date", "Amount", "Accrued", "Net", "Withholding %", "Currency", "Source", "Category", "Notes", "ID", "Original Amount", "Original Accrued", "Original Net", "Original Currency", "Applied Rate", "Rate Date", "Conversion Status"),
        summaryTitle = "Financial Summary (AEAT)", summaryUpdatedLabel = "Updated", summaryCurrencyLabel = "Report currency", summaryExpensesLabel = "Total expenses", summaryIncomeLabel = "Total income", summaryBalanceLabel = "Balance", summaryPendingLabel = "Pending conversions", conversionOkLabel = CONVERSION_OK, conversionLocalLabel = "local currency", conversionPendingLabel = "pending rate"
    )

    val RECIBIDAS: String get() = es.recibidasTitle
    val INGRESOS: String get() = es.ingresosTitle
    val PRODUCTOS: String get() = es.productosTitle
    val RESUMEN: String get() = es.resumenTitle

    val recibidasHeaders: List<Any> get() = es.recibidasHeaders
    val productosHeaders: List<Any> get() = es.productosHeaders
    val ingresosHeaders: List<Any> get() = es.ingresosHeaders

    const val RECIBIDAS_KEY_COLUMN = "O"
    const val RECIBIDAS_LAST_COLUMN = "U"
    const val INGRESOS_KEY_COLUMN = "K"
    const val INGRESOS_LAST_COLUMN = "R"
    const val PRODUCTOS_PARENT_COLUMN = "H"
    const val PRODUCTOS_LAST_COLUMN = "P"

    fun descriptor(locale: LocaleCode): Descriptor = if (locale == LocaleCode.EN) en else es

    fun localeFromCode(code: String?): LocaleCode = when (code?.lowercase(Locale.ROOT)) { SCHEMA_LOCALE_EN -> LocaleCode.EN else -> LocaleCode.ES }

    fun detectLocale(appProperties: Map<String, String>?, sheetTitles: List<String>?, headers: List<String>?): LocaleCode {
        val fromMeta = localeFromCode(appProperties?.get("finaiSchemaLocale"))
        if (appProperties?.get("finaiSchemaLocale") in listOf(SCHEMA_LOCALE_ES, SCHEMA_LOCALE_EN)) return fromMeta
        val titles = sheetTitles.orEmpty().toSet()
        if (titles.intersect(setOf(en.recibidasTitle, en.ingresosTitle, en.productosTitle, en.resumenTitle)).isNotEmpty()) return LocaleCode.EN
        if (titles.intersect(setOf(es.recibidasTitle, es.ingresosTitle, es.productosTitle, es.resumenTitle)).isNotEmpty()) return LocaleCode.ES
        val h = headers.orEmpty().toSet()
        if (h.intersect(setOf(en.recibidasHeaders.first().toString(), en.ingresosHeaders.first().toString(), en.productosHeaders.first().toString(), en.summaryTitle)).isNotEmpty()) return LocaleCode.EN
        return LocaleCode.ES
    }

    data class ConversionSnapshot(
        val targetCurrency: String,
        val locale: LocaleCode = LocaleCode.ES,
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
                    status = descriptor(locale).conversionPendingLabel
                )
                meta.wasNative -> ConvertedAmount(
                    convertedAmount = round2(meta.amount),
                    originalAmount = round2(amount),
                    originalCurrency = normalizedCurrency,
                    appliedRate = 1.0,
                    rateTimestampLabel = "",
                    status = descriptor(locale).conversionLocalLabel
                )
                else -> ConvertedAmount(
                    convertedAmount = round2(meta.amount),
                    originalAmount = round2(amount),
                    originalCurrency = normalizedCurrency,
                    appliedRate = round6(meta.rateApplied),
                    rateTimestampLabel = meta.asOf?.let { formatTimestamp(it, locale) }.orEmpty(),
                    status = descriptor(locale).conversionOkLabel
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
        descriptor: Descriptor,
        exportDate: String,
        reportCurrency: String,
        totals: SummaryTotals
    ): List<List<Any>> = listOf(
        listOf(descriptor.summaryTitle),
        listOf(descriptor.summaryUpdatedLabel, exportDate),
        listOf(descriptor.summaryCurrencyLabel, reportCurrency),
        listOf(descriptor.summaryExpensesLabel, totals.totalExpenses),
        listOf(descriptor.summaryIncomeLabel, totals.totalIncomes),
        listOf(descriptor.summaryBalanceLabel, totals.balance),
        listOf(descriptor.summaryPendingLabel, totals.pendingConversions)
    )

    private fun displayCategoryWithSubcategory(
        category: String?,
        subcategory: String?,
        locale: LocaleCode
    ): String {
        val language = locale.code
        val categoryLabel = TransactionCategories.displayCategory(category, language)
        val subcategoryLabel = TransactionCategories.normalizeCategory(subcategory)
            ?.let { TransactionCategories.displayCategory(it, language) }
            ?: return categoryLabel
        return "$categoryLabel / $subcategoryLabel"
    }

    fun expenseRow(invoice: Invoice, conversion: ConversionSnapshot): List<Any> {
        val total = conversion.convert(invoice.total, invoice.moneda)
        val convertedTotal = total.convertedAmount
        val convertedBase = invoice.baseImponible?.let {
            conversion.convert(it, invoice.moneda).convertedAmount
        } ?: convertedTotal?.let {
            if (invoice.ivaPercent > 0) round2(it / (1 + invoice.ivaPercent / 100.0)) else round2(it)
        }
        val convertedQuota = invoice.cuotaIva?.let {
            conversion.convert(it, invoice.moneda).convertedAmount
        } ?: if (convertedTotal != null && convertedBase != null) {
            round2(convertedTotal - convertedBase)
        } else {
            null
        }
        return listOf(
            invoice.numeroFactura ?: extractFromOcr(invoice.ocrRawText, "numero_factura"),
            formatDate(invoice.fecha, conversion.locale),
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
            displayCategoryWithSubcategory(invoice.categoria, invoice.subcategoria, conversion.locale),
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
        val amount = conversion.convert(income.monto, income.moneda)
        val devengado = income.totalDevengado.takeIf { it > 0.0 }
            ?.let { conversion.convert(it, income.moneda) }
        val liquido = income.totalNeto.takeIf { it > 0.0 }
            ?.let { conversion.convert(it, income.moneda) }
        return listOf(
            income.concepto,
            formatDate(income.fecha, conversion.locale),
            amount.convertedAmount ?: "",
            devengado?.convertedAmount ?: "",
            liquido?.convertedAmount ?: "",
            income.irpfPercent,
            conversion.targetCurrency,
            income.fuente ?: "",
            displayCategoryWithSubcategory(income.categoria, income.subcategoria, conversion.locale),
            income.notas ?: "",
            income.id,
            amount.originalAmount,
            devengado?.originalAmount ?: "",
            liquido?.originalAmount ?: "",
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
        val primary = if (subtotal.status == descriptor(conversion.locale).conversionPendingLabel) subtotal else totalWithVat
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

    private fun formatDate(timestamp: Long, locale: LocaleCode): String =
        SimpleDateFormat("dd/MM/yyyy", javaLocale(locale)).format(Date(timestamp))

    private fun formatTimestamp(timestamp: Long, locale: LocaleCode): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", javaLocale(locale)).format(Date(timestamp))

    private fun javaLocale(locale: LocaleCode): Locale =
        if (locale == LocaleCode.EN) Locale.US else Locale.forLanguageTag("es-ES")

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
