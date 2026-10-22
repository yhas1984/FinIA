package com.gastos.feature.backup

import com.gastos.domain.model.CountryFiscalConfig
import com.gastos.domain.model.TaxPeriodType
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Etiquetas del informe. Se pasan desde recursos para que el motor siga sin depender de Android. */
data class FiscalSummaryLabels(
    val title: String,
    val period: String,
    val country: String,
    val currency: String,
    val outputBlock: String,
    val inputBlock: String,
    val rate: String,
    val base: String,
    val quota: String,
    val documents: String,
    val outputTotal: String,
    val inputTotal: String,
    val result: String,
    val resultToPay: String,
    val resultToOffset: String,
    val irpfWithheld: String,
    val irpfPaid: String,
    val excludedTitle: String,
    val excludedForeignCurrency: String,
    val excludedMissingTaxData: String,
    val excludedMissingRate: String,
    val excludedExempt: String,
    val disclaimer: String,
    val deadline: String,
    val empty: String,
    /** Nombre de la declaración del país, vacío si no está verificado. */
    val declarationName: String = "",
    /** Aviso para países cuyas reglas todavía no están verificadas. */
    val unverifiedCountry: String = ""
)

/** Convierte el resumen en líneas de texto (para el PDF) y en filas de CSV. Sin Android. */
object FiscalSummaryText {

    fun money(value: BigDecimal, currency: String): String {
        val symbols = DecimalFormatSymbols.getInstance(Locale.getDefault())
        val format = DecimalFormat("#,##0.00", symbols)
        return "${format.format(value)} $currency"
    }

    /** Solo el número, con separador local y dos decimales. La moneda va en la cabecera de la columna. */
    fun number(value: BigDecimal): String {
        val symbols = DecimalFormatSymbols.getInstance(Locale.getDefault())
        return DecimalFormat("#,##0.00", symbols).format(value)
    }

    fun rate(value: BigDecimal): String {
        val symbols = DecimalFormatSymbols.getInstance(Locale.getDefault())
        val format = DecimalFormat("#,##0.##", symbols)
        return "${format.format(value)} %"
    }

    fun lines(
        summary: FiscalSummary,
        labels: FiscalSummaryLabels,
        fiscalConfig: CountryFiscalConfig? = null
    ): List<String> {
        val out = mutableListOf<String>()
        out += labels.title
        val period = summary.period.label()
        val deadline = summary.period.deadlineHint(fiscalConfig)
        val head = StringBuilder("${labels.period}: $period")
        if (deadline != null) head.append(" · ${labels.deadline}: $deadline")
        out += head.toString()
        if (summary.countryCode.isNotBlank()) out += "${labels.country}: ${summary.countryCode}"
        out += "${labels.currency}: ${summary.currency}"
        out += ""

        fun block(title: String, block: VatBlock, totalLabel: String) {
            out += title
            if (block.lines.isEmpty()) {
                out += "  ${labels.empty}"
            } else {
                out += "  ${labels.rate} | ${labels.base} | ${labels.quota} | ${labels.documents}"
                block.lines.forEach { line ->
                    out += "  ${rate(line.rate)} | ${money(line.base, summary.currency)} | " +
                        "${money(line.quota, summary.currency)} | ${line.documents}"
                }
            }
            out += "  $totalLabel: ${money(block.base, summary.currency)} · ${money(block.quota, summary.currency)} · ${block.documents}"
            out += ""
        }

        block(summary.taxName + " — " + labels.outputBlock, summary.output, labels.outputTotal)
        block(summary.taxName + " — " + labels.inputBlock, summary.input, labels.inputTotal)

        val resultLabel = if (summary.isPayable) labels.resultToPay else labels.resultToOffset
        out += "$resultLabel: ${money(summary.result.abs(), summary.currency)}"
        if (summary.irpfWithheld.signum() != 0) {
            out += "${labels.irpfWithheld}: ${money(summary.irpfWithheld, summary.currency)}"
        }
        if (summary.irpfPaid.signum() != 0) {
            out += "${labels.irpfPaid}: ${money(summary.irpfPaid, summary.currency)}"
        }

        if (summary.excluded.isNotEmpty()) {
            out += ""
            out += labels.excludedTitle
            summary.excluded[VatExclusion.FOREIGN_CURRENCY]?.let { out += "  ${labels.excludedForeignCurrency}: $it" }
            summary.excluded[VatExclusion.MISSING_TAX_DATA]?.let { out += "  ${labels.excludedMissingTaxData}: $it" }
            summary.excluded[VatExclusion.MISSING_RATE]?.let { out += "  ${labels.excludedMissingRate}: $it" }
            summary.excluded[VatExclusion.EXEMPT]?.let { out += "  ${labels.excludedExempt}: $it" }
        }

        if (fiscalConfig?.tipoPeriodo == TaxPeriodType.USER_DEFINED) {
            out += ""
            out += labels.unverifiedCountry
        }
        out += ""
        out += labels.disclaimer
        return out
    }

    fun csvRows(
        summary: FiscalSummary,
        labels: FiscalSummaryLabels,
        fiscalConfig: CountryFiscalConfig? = null
    ): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        rows += listOf(labels.title)
        rows += listOf(labels.period, summary.period.label())
        summary.period.deadlineHint(fiscalConfig)?.let { rows += listOf(labels.deadline, it) }
        rows += listOf(labels.country, summary.countryCode)
        rows += listOf(labels.currency, summary.currency)
        rows.add(emptyList())
        rows += listOf("bloque", labels.rate, labels.base, labels.quota, labels.documents)
        summary.output.lines.forEach { line ->
            rows += listOf(labels.outputBlock, line.rate.toPlainString(), line.base.toPlainString(), line.quota.toPlainString(), line.documents.toString())
        }
        rows += listOf(labels.outputBlock, "TOTAL", summary.output.base.toPlainString(), summary.output.quota.toPlainString(), summary.output.documents.toString())
        summary.input.lines.forEach { line ->
            rows += listOf(labels.inputBlock, line.rate.toPlainString(), line.base.toPlainString(), line.quota.toPlainString(), line.documents.toString())
        }
        rows += listOf(labels.inputBlock, "TOTAL", summary.input.base.toPlainString(), summary.input.quota.toPlainString(), summary.input.documents.toString())
        rows.add(emptyList())
        rows += listOf(labels.result, summary.result.toPlainString())
        rows += listOf(labels.irpfWithheld, summary.irpfWithheld.toPlainString())
        rows += listOf(labels.irpfPaid, summary.irpfPaid.toPlainString())
        summary.excluded[VatExclusion.FOREIGN_CURRENCY]?.let { rows += listOf(labels.excludedForeignCurrency, it.toString()) }
        summary.excluded[VatExclusion.MISSING_TAX_DATA]?.let { rows += listOf(labels.excludedMissingTaxData, it.toString()) }
        summary.excluded[VatExclusion.MISSING_RATE]?.let { rows += listOf(labels.excludedMissingRate, it.toString()) }
        summary.excluded[VatExclusion.EXEMPT]?.let { rows += listOf(labels.excludedExempt, it.toString()) }
        rows.add(emptyList())
        rows += listOf(labels.disclaimer)
        return rows
    }
}
