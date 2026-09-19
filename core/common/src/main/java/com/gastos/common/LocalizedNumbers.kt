package com.gastos.common

import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Strict, whole-input parsing shared by form validation and persistence. */
object LocalizedNumbers {
    fun parse(text: String, locale: Locale = Locale.getDefault()): Double? {
        val input = text.trim()
        if (input.isEmpty()) return null
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val decimal = symbols.decimalSeparator
        val grouping = symbols.groupingSeparator
        val signless = input.removePrefix("-").removePrefix("+")
        val sign = if (input.startsWith('-')) "-" else ""
        val integerPattern = "(?:[0-9]+|[0-9]{1,3}(?:${Regex.escape(grouping.toString())}[0-9]{3})+)"
        val nativePattern = Regex("$integerPattern(?:${Regex.escape(decimal.toString())}[0-9]+)?")
        val normalized = when {
            nativePattern.matches(signless) -> signless.replace(grouping.toString(), "").replace(decimal, '.')
            // An alternate separator is accepted only if the native grammar did not
            // recognize it as a valid grouping. Mixed or malformed groupings fail.
            Regex("[0-9]+[${if (decimal == ',') "." else ","}][0-9]+").matches(signless) ->
                signless.replace(',', '.')
            else -> return null
        }
        if (input.startsWith("-+") || input.startsWith("+-")) return null
        return (sign + normalized).toDoubleOrNull()?.takeIf(Double::isFinite)
    }

    fun format(value: Double, locale: Locale = Locale.getDefault()): String {
        require(value.isFinite())
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
            .replace('.', DecimalFormatSymbols.getInstance(locale).decimalSeparator)
    }
}
