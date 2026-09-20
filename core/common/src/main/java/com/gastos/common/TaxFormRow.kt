package com.gastos.common

import com.gastos.domain.model.DocumentTax
import com.gastos.domain.model.DocumentTaxes
import com.gastos.domain.model.TaxEffect
import com.gastos.domain.model.TaxTreatment
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency
import java.util.Locale

data class TaxFormRow(
    val name: String = "",
    val rate: String = "",
    val base: String = "",
    val amount: String = "",
    val treatment: TaxTreatment = TaxTreatment.TAXABLE,
    val effect: TaxEffect = TaxEffect.CHARGE
) {
    fun parse(locale: Locale, currency: String): DocumentTax? {
        val values: List<Pair<String, Double?>> = listOf(rate, base, amount).map { it to LocalizedNumbers.parse(it, locale) }
        if (values.any { (text, value) -> text.isNotBlank() && (value == null || value < 0) }) return null
        val percentage: Double? = values[0].second
        val taxableBase: Double? = values[1].second
        val digits: Int = runCatching { Currency.getInstance(currency).defaultFractionDigits }.getOrDefault(2).coerceAtLeast(0)
        val quota: Double? = values[2].second ?: if (percentage != null && taxableBase != null) {
            BigDecimal.valueOf(taxableBase).multiply(BigDecimal.valueOf(percentage)).movePointLeft(2)
                .setScale(digits, RoundingMode.HALF_UP).toDouble()
        } else null
        return DocumentTax(name.trim().takeIf(String::isNotEmpty), percentage, taxableBase, quota, treatment, effect)
    }

    companion object {
        fun from(tax: DocumentTax, locale: Locale): TaxFormRow = TaxFormRow(
            tax.name.orEmpty(), tax.rate?.let { LocalizedNumbers.format(it, locale) }.orEmpty(),
            tax.base?.let { LocalizedNumbers.format(it, locale) }.orEmpty(),
            tax.amount?.let { LocalizedNumbers.format(it, locale) }.orEmpty(), tax.treatment, tax.effect)

        fun parseAll(rows: List<TaxFormRow>, locale: Locale, currency: String): List<DocumentTax>? {
            val parsed: List<DocumentTax> = rows.map { it.parse(locale, currency) ?: return null }
            return parsed.takeIf { DocumentTaxes.validate(it, currency).isEmpty() }
        }
    }
}

data class TaxFormTotals(val base: Double, val charges: Double, val withholding: Double)

/** The document base is entered or derived from its total, never by adding component bases. */
fun reconcileTaxForm(taxes: List<DocumentTax>, total: Double, base: Double?, withholding: Double?, currency: String): TaxFormTotals? {
    if (DocumentTaxes.validate(taxes, currency).isNotEmpty()) return null
    val charges: Double = DocumentTaxes.total(taxes, TaxEffect.CHARGE) ?: return null
    val retained: Double = if (taxes.any { it.effect == TaxEffect.WITHHOLDING })
        DocumentTaxes.total(taxes, TaxEffect.WITHHOLDING) ?: return null else withholding ?: 0.0
    val netBase: BigDecimal = DocumentTaxes.decimal(base) ?: (BigDecimal.valueOf(total) - BigDecimal.valueOf(charges) + BigDecimal.valueOf(retained))
    if (netBase < BigDecimal.ZERO || (netBase + BigDecimal.valueOf(charges) - BigDecimal.valueOf(retained) - BigDecimal.valueOf(total)).abs() > DocumentTaxes.tolerance(currency)) return null
    return TaxFormTotals(netBase.toDouble(), charges, retained)
}
