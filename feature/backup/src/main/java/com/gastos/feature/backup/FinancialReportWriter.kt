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

enum class ReportFormat(val extension: String, val mime: String) { CSV("csv", "text/csv"), PDF("pdf", "application/pdf") }

private fun StringBuilder.appendCsvRow(vararg values: Any?) {
    append(values.joinToString(",") { value ->
        val raw = value?.toString().orEmpty()
        val safe = if (value is String && raw.firstOrNull() in setOf('=', '+', '-', '@')) {
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
    suspend fun generate(format: ReportFormat, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
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
            val invoices: List<Invoice> = data.invoices.filter { it.tipo == InvoiceType.GASTO }
            val incomes: List<Income> = mergeIncomes(data.invoices, data.incomes)
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
                val csv: String = buildCsvContent(invoices, incomes, data.products, target, totals, progress)
                currentCoroutineContext().ensureActive()
                file.writeText(csv, Charsets.UTF_8)
            } else writePdf(file, invoices, incomes, target, totals, progress)
            currentCoroutineContext().ensureActive()
            progress(1f)
            file
        } catch (error: Throwable) { file.delete(); throw error }
    }
    private fun convertedText(summary: ConversionSummary, currency: String): String =
        summary.amount?.let { com.gastos.domain.model.formatMoney(it, currency) +
            if (summary.isPartial) " · " + context.getString(R.string.total_partial) else "" }
            ?: context.getString(R.string.total_unavailable)
    private suspend fun buildCsvContent(
        invoices: List<Invoice>,
        incomes: List<Income>,
        products: List<Product>,
        target: String,
        totals: Pair<ConversionSummary, ConversionSummary>,
        progress: (Float) -> Unit
    ): String = buildString {
        append('\uFEFF')
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
            "taxes_json_original_currency", "document_uuid", "parent_document_uuid"
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
                com.gastos.domain.model.DocumentTaxCodec.encode(invoice.taxes), invoice.documentUuid, ""
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
                0,
                "",
                "",
                "",
                "",
                "",
                com.gastos.domain.model.DocumentTaxCodec.encode(product.taxes), "", parentUuid
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
                com.gastos.domain.model.DocumentTaxCodec.encode(income.taxes), income.documentUuid, ""
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


    private suspend fun writePdf(file: File, invoices: List<Invoice>, incomes: List<Income>, currency: String, totals: Pair<ConversionSummary, ConversionSummary>, progress: (Float) -> Unit) {
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
            line(context.getString(R.string.pdf_title), true)
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
                invoice.numeroFactura?.let { line(it) }
                invoice.taxes.forEach { line(com.gastos.common.describeTax(context, it, invoice.moneda)) }
                progress(0.1f + 0.85f * ++completed / count)
            }
            line(context.getString(R.string.pdf_income), true)
            incomes.forEach { income ->
                line("${date.format(Date(income.fecha))} · ${formatMoney(income.monto, income.moneda)}", true)
                line(income.concepto)
                income.taxes.forEach { line(com.gastos.common.describeTax(context, it, income.moneda)) }
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
