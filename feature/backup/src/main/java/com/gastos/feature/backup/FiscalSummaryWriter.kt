package com.gastos.feature.backup

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.gastos.domain.model.CountryFiscalConfig
import com.gastos.domain.model.TaxPeriodType
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * Escribe el resumen de IVA en PDF o CSV. Solo dibuja: el cálculo vive en [VatSummaryCalculator]
 * y el texto en [FiscalSummaryText], que se pueden probar sin Android.
 */
class FiscalSummaryWriter @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun generate(
        summary: FiscalSummary,
        labels: FiscalSummaryLabels,
        format: ReportFormat,
        fiscalConfig: CountryFiscalConfig? = null
    ): File = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "fiscal_exports").apply { mkdirs() }
        val name = "finai_iva_${summary.period.year}_${summary.period.quarter ?: summary.period.month}_${UUID.randomUUID()}"
        val file = File(folder, "$name.${format.extension}")
        // El descargo nombra el formulario español solo cuando el país es España; en el resto se
        // usa uno genérico que no promete nada de un sistema que no es el suyo.
        val effectiveLabels = if (fiscalConfig?.paisCodigo?.uppercase() == "ES") labels
            else labels.copy(disclaimer = context.getString(R.string.fiscal_disclaimer_generic))
        when (format) {
            ReportFormat.PDF -> writePdf(file, summary, effectiveLabels, fiscalConfig)
            ReportFormat.CSV -> writeCsv(file, summary, effectiveLabels, fiscalConfig)
        }
        file
    }

    private fun writeCsv(
        file: File,
        summary: FiscalSummary,
        labels: FiscalSummaryLabels,
        fiscalConfig: CountryFiscalConfig?
    ) {
        val rows = FiscalSummaryText.csvRows(summary, labels, fiscalConfig)
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write('\uFEFF'.code) // BOM para que Excel abra bien los acentos
            rows.forEach { row ->
                writer.write(row.joinToString(";") { cell -> escape(cell) })
                writer.write("\r\n")
            }
        }
    }

    private fun escape(value: String): String =
        if (value.contains(';') || value.contains('"') || value.contains('\n')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value

    private suspend fun writePdf(
        file: File,
        summary: FiscalSummary,
        labels: FiscalSummaryLabels,
        fiscalConfig: CountryFiscalConfig?
    ) {
        val pdf = PdfDocument()
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f; color = INK }
        val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f; color = MUTED }
        val header = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 19f; color = 0xFFFFFFFF.toInt(); isFakeBoldText = true }
        val headerSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f; color = 0xFFEDE6FB.toInt() }
        val section = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11.5f; color = INK; isFakeBoldText = true }
        val cell = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f; color = INK }
        val cellBold = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f; color = INK; isFakeBoldText = true }
        val cellRight = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f; color = INK; textAlign = Paint.Align.RIGHT }
        val headCell = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f; color = MUTED }
        val headCellRight = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f; color = MUTED; textAlign = Paint.Align.RIGHT }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2EEFA.toInt() }
        val fillSoft = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF8F6FD.toInt() }
        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE2DCF2.toInt(); strokeWidth = 1f }
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PURPLE }
        val resultLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = PURPLE; isFakeBoldText = true }
        val resultValue = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 26f; color = PURPLE; isFakeBoldText = true }

        val margin = 48f
        val right = 547f
        val rateX = margin + 8f
        val baseRight = 320f
        val quotaRight = 425f
        val docsRight = right - 8f

        var page: PdfDocument.Page? = null
        try {
            var number = 1
            var current = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, number).create())
            page = current
            var canvas = current.canvas
            var y = 0f

            fun drawHeader() {
                canvas.drawRect(0f, 0f, 595f, 96f, accent)
                canvas.drawText(labels.title, margin, 44f, header)
                val deadline = summary.period.deadlineHint(fiscalConfig)
                val sub = summary.period.label() + if (deadline != null) "   ·   " + labels.deadline + ": " + deadline else ""
                canvas.drawText(sub, margin, 66f, headerSub)
                val meta = (if (summary.countryCode.isNotBlank()) labels.country + ": " + summary.countryCode + "   ·   " else "") +
                    labels.currency + ": " + summary.currency
                canvas.drawText(meta, margin, 84f, headerSub)
                y = 132f
            }

            fun newPage() {
                pdf.finishPage(current)
                current = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, ++number).create())
                page = current
                canvas = current.canvas
                y = 0f
                drawHeader()
            }

            fun ensureRoom(step: Float) {
                if (y + step > 800f) newPage()
            }

            fun wrap(text: String, brush: Paint, maxWidth: Float): List<String> {
                val out = mutableListOf<String>()
                var remaining = text
                while (remaining.isNotEmpty()) {
                    val limit = brush.breakText(remaining, true, maxWidth, null).coerceAtLeast(1)
                    val space = remaining.take(limit).indexOfLast(Char::isWhitespace)
                    val cut = if (limit < remaining.length && space > 0) space + 1 else limit
                    out += remaining.take(cut).trim()
                    remaining = remaining.drop(cut).trimStart()
                }
                return out
            }

            fun paragraph(text: String, brush: Paint, top: Float = 4f, lineGap: Float = 13f) {
                y += top
                wrap(text, brush, right - margin).forEach { l ->
                    ensureRoom(lineGap)
                    canvas.drawText(l, margin, y, brush)
                    y += lineGap
                }
            }

            fun block(title: String, block: VatBlock, totalLabel: String) {
                ensureRoom(90f)
                y += 10f
                canvas.drawText(title, margin, y, section)
                y += 8f
                canvas.drawRect(margin, y, right, y + 20f, fill)
                canvas.drawText(labels.rate, rateX, y + 14f, headCell)
                canvas.drawText(labels.base + " (" + summary.currency + ")", baseRight, y + 14f, headCellRight)
                canvas.drawText(labels.quota + " (" + summary.currency + ")", quotaRight, y + 14f, headCellRight)
                canvas.drawText(labels.documents, docsRight, y + 14f, headCellRight)
                y += 20f
                if (block.lines.isEmpty()) {
                    val center = margin + (right - margin) / 2f - muted.measureText(labels.empty) / 2f
                    canvas.drawText(labels.empty, center, y + 16f, muted)
                    y += 26f
                } else {
                    block.lines.forEachIndexed { index, line ->
                        ensureRoom(24f)
                        if (index % 2 == 1) canvas.drawRect(margin, y, right, y + 22f, fillSoft)
                        canvas.drawText(FiscalSummaryText.rate(line.rate), rateX, y + 15f, cell)
                        canvas.drawText(FiscalSummaryText.number(line.base), baseRight, y + 15f, cellRight)
                        canvas.drawText(FiscalSummaryText.number(line.quota), quotaRight, y + 15f, cellRight)
                        canvas.drawText(line.documents.toString(), docsRight, y + 15f, cellRight)
                        y += 22f
                    }
                }
                canvas.drawLine(margin, y, right, y, rule)
                y += 6f
                canvas.drawText(totalLabel, rateX, y + 14f, cellBold)
                canvas.drawText(FiscalSummaryText.number(block.base), baseRight, y + 14f, cellBold)
                canvas.drawText(FiscalSummaryText.number(block.quota), quotaRight, y + 14f, cellBold)
                canvas.drawText(block.documents.toString(), docsRight, y + 14f, cellBold)
                y += 30f
            }

            drawHeader()
            block(summary.taxName + " — " + labels.outputBlock, summary.output, labels.outputTotal)
            block(summary.taxName + " — " + labels.inputBlock, summary.input, labels.inputTotal)

            // Caja del resultado
            ensureRoom(110f)
            val resultText = if (summary.isPayable) labels.resultToPay else labels.resultToOffset
            canvas.drawRect(margin, y, right, y + 78f, fill)
            canvas.drawRect(margin, y, margin + 5f, y + 78f, accent)
            canvas.drawText(resultText, margin + 20f, y + 30f, resultLabel)
            canvas.drawText(FiscalSummaryText.money(summary.result.abs(), summary.currency), margin + 16f, y + 62f, resultValue)
            var noteY = y + 30f
            if (summary.irpfWithheld.signum() != 0) {
                val t = labels.irpfWithheld + ": " + FiscalSummaryText.money(summary.irpfWithheld, summary.currency)
                canvas.drawText(t, right - 16f - muted.measureText(t), noteY, muted)
                noteY += 16f
            }
            if (summary.irpfPaid.signum() != 0) {
                val t = labels.irpfPaid + ": " + FiscalSummaryText.money(summary.irpfPaid, summary.currency)
                canvas.drawText(t, right - 16f - muted.measureText(t), noteY, muted)
            }
            y += 96f

            if (summary.excluded.isNotEmpty()) {
                ensureRoom(60f)
                canvas.drawText(labels.excludedTitle, margin, y, section)
                y += 16f
                summary.excluded[VatExclusion.FOREIGN_CURRENCY]?.let { paragraph("· " + labels.excludedForeignCurrency + ": " + it, muted, top = 0f) }
                summary.excluded[VatExclusion.MISSING_TAX_DATA]?.let { paragraph("· " + labels.excludedMissingTaxData + ": " + it, muted, top = 0f) }
                summary.excluded[VatExclusion.MISSING_RATE]?.let { paragraph("· " + labels.excludedMissingRate + ": " + it, muted, top = 0f) }
                summary.excluded[VatExclusion.EXEMPT]?.let { paragraph("· " + labels.excludedExempt + ": " + it, muted, top = 0f) }
                y += 8f
            }

            if (fiscalConfig?.tipoPeriodo == TaxPeriodType.USER_DEFINED) {
                // País sin reglas verificadas: se dice claramente, en vez de nombrar un formulario
                // que no le corresponde.
                ensureRoom(30f)
                y += 4f
                paragraph(labels.unverifiedCountry, muted, top = 0f, lineGap = 12f)
            }

            ensureRoom(60f)
            canvas.drawLine(margin, y, right, y, rule)
            y += 16f
            paragraph(labels.disclaimer, muted, top = 0f, lineGap = 12f)
            currentCoroutineContext().ensureActive()
            pdf.finishPage(current)
            file.outputStream().use { pdf.writeTo(it) }
        } finally {
            page?.let { runCatching { pdf.finishPage(it) } }
            pdf.close()
        }
    }

    companion object {
        private const val PURPLE = 0xFF7650C7.toInt()
        private const val INK = 0xFF241A3D.toInt()
        private const val MUTED = 0xFF6B6478.toInt()

        fun labelsOf(context: Context): FiscalSummaryLabels = FiscalSummaryLabels(
            title = context.getString(R.string.fiscal_title),
            period = context.getString(R.string.fiscal_period),
            country = context.getString(R.string.fiscal_country),
            currency = context.getString(R.string.fiscal_currency),
            outputBlock = context.getString(R.string.fiscal_output_block),
            inputBlock = context.getString(R.string.fiscal_input_block),
            rate = context.getString(R.string.fiscal_rate),
            base = context.getString(R.string.fiscal_base),
            quota = context.getString(R.string.fiscal_quota),
            documents = context.getString(R.string.fiscal_documents),
            outputTotal = context.getString(R.string.fiscal_output_total),
            inputTotal = context.getString(R.string.fiscal_input_total),
            result = context.getString(R.string.fiscal_result),
            resultToPay = context.getString(R.string.fiscal_result_to_pay),
            resultToOffset = context.getString(R.string.fiscal_result_to_offset),
            irpfWithheld = context.getString(R.string.fiscal_irpf_withheld),
            irpfPaid = context.getString(R.string.fiscal_irpf_paid),
            excludedTitle = context.getString(R.string.fiscal_excluded_title),
            excludedForeignCurrency = context.getString(R.string.fiscal_excluded_currency),
            excludedMissingTaxData = context.getString(R.string.fiscal_excluded_missing),
            excludedMissingRate = context.getString(R.string.fiscal_excluded_rate),
            excludedExempt = context.getString(R.string.fiscal_excluded_exempt),
            disclaimer = context.getString(R.string.fiscal_disclaimer),
            deadline = context.getString(R.string.fiscal_deadline),
            empty = context.getString(R.string.fiscal_empty),
            declarationName = "",
            unverifiedCountry = context.getString(R.string.fiscal_country_unverified)
        )

        /** Bloque de cálculo a partir de los documentos del usuario. */
        fun summaryOf(
            period: VatPeriod,
            currency: String,
            invoices: List<Invoice>,
            incomes: List<Income>,
            fiscalConfig: CountryFiscalConfig?
        ): FiscalSummary = VatSummaryCalculator.summary(
            period = period,
            currency = currency,
            invoices = invoices,
            incomes = incomes,
            fiscalConfig = fiscalConfig
        )
    }
}
