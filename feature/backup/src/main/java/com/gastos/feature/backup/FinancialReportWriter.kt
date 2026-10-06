package com.gastos.feature.backup

import android.content.Context
import android.graphics.pdf.PdfDocument
import com.gastos.domain.model.*
import com.gastos.repository.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

enum class ReportDetail { SUMMARY, DETAILED }

enum class ReportFormat(val extension: String, val mime: String) { CSV("csv", "text/csv"), PDF("pdf", "application/pdf") }

internal fun Appendable.appendCsvRow(vararg values: Any?) {
    append(values.joinToString(",") { value ->
        val raw = value?.toString().orEmpty()
        val safe = if (value is String && raw.trimStart { it <= ' ' }.firstOrNull() in setOf('=', '+', '-', '@')) {
            "'$raw"
        } else {
            raw
        }
        "\"${safe.replace("\"", "\"\"")}\""
    })
    append('\n')
}

/** Generates from one database snapshot. Only temporary report files expire. */
class FinancialReportWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: BackupDataRepository,
    private val currencyPreference: CurrencyPreference,
    private val exchangeRateProvider: ExchangeRateProvider
) {
    suspend fun generate(format: ReportFormat, filter: ReportFilter = ReportFilter(), progress: (Float) -> Unit): File =
        generateReport(format, filter, ReportDetail.SUMMARY, progress)

    suspend fun generateReport(format: ReportFormat, filter: ReportFilter, detail: ReportDetail, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val folder: File = File(context.cacheDir, "report_exports")
        check(folder.isDirectory || folder.mkdirs())
        folder.listFiles()?.filter { it.isFile && it.lastModified() < System.currentTimeMillis() - RETENTION_MILLIS }?.forEach(File::delete)
        val oldExports: File = File(context.filesDir, "exports")
        oldExports.listFiles()?.filter { it.isFile && it.name.matches(Regex("finai_backup_[0-9_]+\\.csv")) &&
            it.lastModified() < System.currentTimeMillis() - RETENTION_MILLIS }?.forEach(File::delete)
        val file: File = File(folder, "finai_report_${System.currentTimeMillis()}_${UUID.randomUUID()}.${format.extension}")
        try {
            progress(0.05f)
            val data: BackupDataset = repository.financialSnapshot()
            val invoices: List<Invoice> = data.invoices.filter { it.tipo == InvoiceType.GASTO && filter.includes(it.fecha, DocumentKind.EXPENSE, it.categoria, it.subcategoria) }
            val incomes: List<Income> = mergeIncomes(data.invoices, data.incomes).filter { filter.includes(it.fecha, DocumentKind.INCOME, it.categoria, it.subcategoria) }
            val target: String = currencyPreference.defaultCurrency.value
            val rates: Map<String, Double> = exchangeRateProvider.rates.value.toMap()
            val updatedAt: Long? = exchangeRateProvider.lastUpdated.value
            fun convert(amount: Double, from: String, to: String): Double? {
                if (from.equals(to, ignoreCase = true)) return amount
                val origin: Double = rates[from.uppercase(Locale.ROOT)] ?: return null
                val destination: Double = rates[to.uppercase(Locale.ROOT)] ?: return null
                return if (origin.isFinite() && destination.isFinite() && origin > 0 && destination > 0)
                    (amount * destination / origin).takeIf(Double::isFinite) else null
            }
            val totals: Pair<ConversionSummary, ConversionSummary> =
                summarizeConversions(invoices.map { it.moneyRecord() }, target, updatedAt, ::convert) to
                summarizeConversions(incomes.map { it.moneyRecord() }, target, updatedAt, ::convert)
            if (format == ReportFormat.CSV) {
                val parents = invoices.map { it.id }.toSet() + incomes.filter { it.id < 0 }.map { -it.id }
                file.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writeCsvContent(writer, invoices, incomes, data.products.filter { it.invoiceId in parents }, target, totals, progress, filter)
                }
            } else writePdf(file, invoices, incomes, data.products, target, totals, progress, filter, detail)
            currentCoroutineContext().ensureActive()
            progress(1f)
            file
        } catch (error: Throwable) { file.delete(); throw error }
    }
    private fun filterDescription(filter: ReportFilter): String {
        val dates = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        val end = filter.endExclusive?.let { Calendar.getInstance().apply { timeInMillis = it; add(Calendar.DATE, -1) }.time }
        return listOfNotNull(filter.startInclusive?.let { dates.format(Date(it)) }, end?.let(dates::format),
            filter.kind?.let { context.getString(if (it == DocumentKind.EXPENSE) R.string.csv_type_expense else R.string.csv_type_income) }, filter.category, filter.subcategory).joinToString(" · ").ifEmpty { context.getString(R.string.report_all_records) }
    }
    private fun convertedText(summary: ConversionSummary, currency: String): String =
        summary.amount?.let { com.gastos.domain.model.formatMoney(it, currency) +
            if (summary.isPartial) " · " + context.getString(R.string.total_partial) else "" }
            ?: context.getString(R.string.total_unavailable)
    private suspend fun writeCsvContent(
        output: Appendable,
        invoices: List<Invoice>,
        incomes: List<Income>,
        products: List<Product>,
        target: String,
        totals: Pair<ConversionSummary, ConversionSummary>,
        progress: (Float) -> Unit,
        filter: ReportFilter
    ) = with(output) {
        append('\uFEFF')
        appendCsvRow(context.getString(R.string.report_filters), filterDescription(filter))
        appendCsvRow(
            context.getString(R.string.csv_header_type),
            context.getString(R.string.csv_header_id),
            context.getString(R.string.csv_header_date),
            context.getString(R.string.csv_header_invoice_number),
            context.getString(R.string.csv_header_concept),
            context.getString(R.string.csv_header_amount),
            context.getString(R.string.csv_header_currency),
            context.getString(R.string.csv_header_tax_base),
            context.getString(R.string.csv_header_vat_percent),
            context.getString(R.string.csv_header_vat_amount),
            context.getString(R.string.csv_header_irpf_percent),
            context.getString(R.string.csv_header_gross),
            context.getString(R.string.csv_header_net),
            context.getString(R.string.csv_header_category),
            context.getString(R.string.csv_header_subcategory),
            context.getString(R.string.csv_header_notes),
            "taxes_json_original_currency", "document_uuid", "parent_document_uuid", "origin", "manual_amount_adjusted"
        )
        val invoiceById = invoices.associateBy { it.id }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        val totalRows = (invoices.size + products.size + incomes.size).coerceAtLeast(1)
        var completed = 0

        invoices.forEach { invoice ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            progress(0.1f + 0.85f * ++completed / totalRows)
            appendCsvRow(
                 if (invoice.tipo == InvoiceType.GASTO) context.getString(R.string.csv_type_expense) else context.getString(R.string.csv_type_income),
                invoice.id,
                dateFormat.format(Date(invoice.fecha)),
                invoice.numeroFactura.orEmpty(),
                invoice.proveedor,
                invoice.total,
                invoice.moneda,
                invoice.baseImponible ?: "",
                invoice.ivaPercent ?: "",
                invoice.cuotaIva ?: "",
                invoice.irpfPercent,
                "",
                "",
                invoice.categoria.orEmpty(),
                invoice.subcategoria.orEmpty(),
                invoice.notas.orEmpty(),
                com.gastos.domain.model.DocumentTaxCodec.encode(invoice.taxes), invoice.documentUuid, "", invoice.origin, invoice.manualAmountAdjusted
            )
        }
        products.forEach { product ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            progress(0.1f + 0.85f * ++completed / totalRows)
            val parent = invoiceById[product.invoiceId]
            val legacy = incomes.firstOrNull { it.id == -product.invoiceId }
            val parentUuid = parent?.documentUuid ?: legacy?.documentUuid ?: return@forEach
            val parentDate = parent?.fecha ?: requireNotNull(legacy).fecha
            appendCsvRow(
                 context.getString(R.string.csv_type_product),
                product.id,
                dateFormat.format(Date(parentDate)),
                parent?.numeroFactura ?: legacy?.evidence?.document?.number.orEmpty(),
                product.descripcion,
                product.totalIncludingTax ?: "",
                parent?.moneda ?: legacy?.moneda.orEmpty(),
                "",
                product.ivaPercent ?: "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                com.gastos.domain.model.DocumentTaxCodec.encode(product.taxes), "", parentUuid, parent?.origin ?: legacy?.origin.orEmpty(), parent?.manualAmountAdjusted ?: legacy?.manualAmountAdjusted ?: false
            )
        }
        incomes.forEach { income ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            progress(0.1f + 0.85f * ++completed / totalRows)
            appendCsvRow(
                 context.getString(R.string.csv_type_income),
                income.id,
                dateFormat.format(Date(income.fecha)),
                income.evidence?.document?.number.orEmpty(),
                income.concepto,
                income.monto,
                income.moneda,
                income.evidence?.document?.taxBase ?: "",
                income.ivaPercent ?: "",
                income.evidence?.document?.vatAmount ?: "",
                income.irpfPercent,
                income.totalDevengado.takeIf { it > 0 } ?: "",
                income.totalNeto.takeIf { it > 0 } ?: "",
                income.categoria.orEmpty(),
                income.subcategoria.orEmpty(),
                income.notas.orEmpty(),
                com.gastos.domain.model.DocumentTaxCodec.encode(income.taxes), income.documentUuid, "", income.origin, income.manualAmountAdjusted
            )
        }

        val (expenses, revenue) = totals
        val balance = com.gastos.domain.model.partialBalance(expenses, revenue)
        append('\n')
        appendCsvRow(context.getString(R.string.csv_summary), context.getString(R.string.csv_summary_currency), target)
        appendCsvRow(context.getString(R.string.csv_summary_expenses), convertedText(expenses, target))
        appendCsvRow(context.getString(R.string.csv_summary_income), convertedText(revenue, target))
        appendCsvRow(context.getString(R.string.csv_summary_balance), balance?.let { com.gastos.domain.model.formatMoney(it, target) } ?: context.getString(R.string.total_unavailable),
            if (expenses.isPartial || revenue.isPartial) context.getString(R.string.total_partial) else "")
        (expenses.excluded + revenue.excluded).forEach {
            appendCsvRow(context.getString(R.string.total_partial), it.id, it.description, it.amount, it.currency)
        }
        appendCsvRow(context.getString(R.string.csv_summary_exported_at), SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date()))
    }


    private suspend fun writePdf(file: File, invoices: List<Invoice>, incomes: List<Income>, products: List<Product>, currency: String, totals: Pair<ConversionSummary, ConversionSummary>, progress: (Float) -> Unit, filter: ReportFilter, detail: ReportDetail) {
        val job: kotlin.coroutines.CoroutineContext = currentCoroutineContext()
        val date: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        val paint: android.graphics.Paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f }
        val (expenses, revenue) = totals
        val pdf: PdfDocument = PdfDocument()
        var openPage: PdfDocument.Page? = null
        try {
            var number: Int = 1
            var page: PdfDocument.Page = pdf.startPage(PdfDocument.PageInfo.Builder(595,842,number).create())
            openPage = page
            var y: Float = 48f
            fun line(value: String, bold: Boolean = false) {
                job.ensureActive()
                paint.isFakeBoldText = bold
                var remaining: String = value
                do {
                    job.ensureActive()
                    if (y > 780f) {
                        pdf.finishPage(page)
                        page = pdf.startPage(PdfDocument.PageInfo.Builder(595,842,++number).create())
                        openPage = page
                        y = 48f
                    }
                    val limit: Int = paint.breakText(remaining, true, 499f, null).coerceAtLeast(1)
                    val newline: Int = remaining.indexOf('\n').takeIf { it in 0 until limit } ?: -1
                    val whitespace: Int = remaining.take(limit).indexOfLast(Char::isWhitespace)
                    val count: Int = when {
                        newline >= 0 -> newline + 1
                        limit < remaining.length && whitespace > 0 -> whitespace + 1
                        else -> limit
                    }
                    page.canvas.drawText(remaining.take(count).trimEnd(), 48f, y, paint)
                    y += 17f
                    remaining = remaining.drop(count).trimStart()
                } while (remaining.isNotEmpty())
            }
            fun productLines(rows: List<Product>, currencyCode: String) {
                if (rows.isEmpty()) return
                line(context.getString(R.string.report_products), true)
                rows.forEach { product ->
                    line("${product.descripcion} · ${product.cantidad} × ${formatMoney(product.precioUnitario, currencyCode)}")
                    product.totalIncludingTax?.let { line(formatMoney(it, currencyCode)) }
                    product.taxes.forEach { line(com.gastos.common.describeTax(context, it, currencyCode)) }
                }
            }
            line(context.getString(R.string.pdf_title), true)
            line(filterDescription(filter))
            line(context.getString(R.string.pdf_total_expenses, convertedText(expenses, currency)))
            line(context.getString(R.string.pdf_total_income, convertedText(revenue, currency)))
            val balance: String = partialBalance(expenses, revenue)?.let { formatMoney(it, currency) } ?: context.getString(R.string.total_unavailable)
            line(context.getString(R.string.pdf_balance, balance) + if (expenses.isPartial || revenue.isPartial) " · " + context.getString(R.string.total_partial) else "")
            (expenses.excluded + revenue.excluded).forEach { line("${context.getString(R.string.total_partial)}: ${it.description} · ${formatMoney(it.amount, it.currency)}") }
            val count: Int = (invoices.size + incomes.size).coerceAtLeast(1)
            var completed: Int = 0
            line(context.getString(R.string.pdf_expenses), true)
            invoices.forEach { invoice ->
                line("${date.format(Date(invoice.fecha))} · ${formatMoney(invoice.total, invoice.moneda)}", true)
                line(invoice.proveedor)
                line(listOfNotNull(invoice.categoria, invoice.subcategoria).joinToString(" / "))
                if (invoice.manualAmountAdjusted) line(context.getString(R.string.report_manual_adjustment))
                invoice.numeroFactura?.let { line(it) }
                invoice.taxes.forEach { line(com.gastos.common.describeTax(context, it, invoice.moneda)) }
                if (detail == ReportDetail.DETAILED) {
                    invoice.notas?.takeIf(String::isNotBlank)?.let { line(it) }
                    productLines(products.filter { it.invoiceId == invoice.id }, invoice.moneda)
                }
                progress(0.1f + 0.85f * ++completed / count)
            }
            line(context.getString(R.string.pdf_income), true)
            incomes.forEach { income ->
                line("${date.format(Date(income.fecha))} · ${formatMoney(income.monto, income.moneda)}", true)
                line(income.concepto)
                line(listOfNotNull(income.categoria, income.subcategoria).joinToString(" / "))
                if (income.manualAmountAdjusted) line(context.getString(R.string.report_manual_adjustment))
                income.taxes.forEach { line(com.gastos.common.describeTax(context, it, income.moneda)) }
                if (detail == ReportDetail.DETAILED) {
                    income.evidence?.document?.number?.let { line(it) }
                    income.notas?.takeIf(String::isNotBlank)?.let { line(it) }
                    val document = income.evidence?.document
                    val gross = income.totalDevengado.takeIf { it > 0 } ?: document?.gross
                    val net = income.totalNeto.takeIf { it > 0 } ?: document?.net
                    if (gross != null || net != null || document?.payroll != null) {
                        line(context.getString(R.string.report_payroll), true)
                        gross?.let { line(context.getString(R.string.csv_header_gross) + ": " + formatMoney(it, income.moneda)) }
                        net?.let { line(context.getString(R.string.csv_header_net) + ": " + formatMoney(it, income.moneda)) }
                        document?.payroll?.let { payroll ->
                            listOfNotNull(payroll.periodStart, payroll.periodEnd).joinToString(" – ").takeIf(String::isNotBlank)?.let { line(it) }
                            payroll.lines.forEach { row ->
                                val label = when (row.type) {
                                    PayrollLineType.EARNING -> R.string.report_earning
                                    PayrollLineType.DEDUCTION -> R.string.report_deduction
                                    PayrollLineType.UNKNOWN -> R.string.report_payroll_item
                                }
                                line(listOfNotNull(context.getString(label), row.description,
                                    row.amount?.let { formatMoney(it, income.moneda) }).joinToString(" · "))
                            }
                        }
                    }
                    if (income.id < 0) productLines(products.filter { it.invoiceId == -income.id }, income.moneda)
                    else if (document?.payroll == null && !document?.lines.isNullOrEmpty()) {
                        line(context.getString(R.string.report_products), true)
                        document?.lines?.forEach { row -> line(listOfNotNull(row.description,
                            row.subtotal?.let { formatMoney(it, income.moneda) }).joinToString(" · ")) }
                    }
                }
                progress(0.1f + 0.85f * ++completed / count)
            }
            pdf.finishPage(page)
            openPage = null
            job.ensureActive()
            file.outputStream().use(pdf::writeTo)
        } finally {
            openPage?.let(pdf::finishPage)
            pdf.close()
        }
    }
    companion object { private const val RETENTION_MILLIS: Long = 7L * 24 * 60 * 60 * 1000 }
}
