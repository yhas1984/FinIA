package com.gastos.common

import android.content.Context
import com.gastos.domain.model.DocumentTax
import com.gastos.domain.model.TaxEffect
import com.gastos.domain.model.TaxTreatment
import java.util.Locale

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
    val name: String = tax.name ?: context.getString(R.string.taxes_unknown)
    val rate: String = tax.rate?.let { LocalizedNumbers.format(it, locale) + " %" }.orEmpty()
    val base: String = tax.base?.let { LocalizedNumbers.format(it, locale) }.orEmpty().ifBlank { "—" }
    val amount: String = tax.amount?.let { LocalizedNumbers.format(it, locale) }.orEmpty().ifBlank { "—" }
    return "$name $rate · ${context.getString(treatment)} · ${context.getString(effect)} · " +
        "${context.getString(R.string.taxes_base)}: $base $currency · $amount $currency"
}
