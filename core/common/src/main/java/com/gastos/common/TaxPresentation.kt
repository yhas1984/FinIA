package com.gastos.common

import android.content.Context
import com.gastos.domain.model.DocumentTax
import com.gastos.domain.model.TaxEffect
import com.gastos.domain.model.TaxTreatment
import java.util.Locale

/** Keep the printed label; append the stored rate only when it adds information. */
fun formatTaxName(name: String?, rate: Double?, unknownName: String, locale: Locale): String {
    val label: String = name?.trim()?.takeIf(String::isNotEmpty) ?: unknownName
    if (rate == null) return label
    val percentages: List<MatchResult> = Regex("(?<![\\d.,+-])([0-9]+(?:[.,][0-9]+)?)[\\s\\u00A0\\u202F]*[%٪]")
        .findAll(label).toList()
    val printedRate: Double? = percentages.singleOrNull()?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
    if (printedRate == rate) return label
    return "$label ${LocalizedNumbers.format(rate, locale)} %"
}

fun describeTax(context: Context, tax: DocumentTax, currency: String): String {
    val locale: Locale = context.resources.configuration.locales[0]
    val treatment: Int = when (tax.treatment) {
        TaxTreatment.TAXABLE -> R.string.taxes_taxable
        TaxTreatment.ZERO_RATED -> R.string.taxes_zero
        TaxTreatment.EXEMPT -> R.string.taxes_exempt
        TaxTreatment.OUT_OF_SCOPE -> R.string.taxes_outside
        TaxTreatment.UNKNOWN -> R.string.taxes_unknown
    }
    val effect: Int = if (tax.effect == TaxEffect.CHARGE) R.string.taxes_charge else R.string.taxes_withholding
    val name: String = formatTaxName(tax.name, tax.rate, context.getString(R.string.taxes_unknown), locale)
    val base: String = tax.base?.let { LocalizedNumbers.format(it, locale) }.orEmpty().ifBlank { "—" }
    val amount: String = tax.amount?.let { LocalizedNumbers.format(it, locale) }.orEmpty().ifBlank { "—" }
    return "$name · ${context.getString(treatment)} · ${context.getString(effect)} · " +
        "${context.getString(R.string.taxes_base)}: $base $currency · $amount $currency"
}
