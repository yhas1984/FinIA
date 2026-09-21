package com.gastos.feature.backup

import java.text.DecimalFormatSymbols
import java.util.Locale

/** The workbook locale, not the app language, determines formula argument separators. */
internal object SheetsFormulaLocale {
    fun adapt(formula: String, workbookLocale: String?): String {
        val locale: Locale = Locale.forLanguageTag(workbookLocale.orEmpty().ifBlank { "en-US" }.replace('_', '-'))
        if (DecimalFormatSymbols.getInstance(locale).decimalSeparator != ',') return formula
        var quote: Char? = null
        return buildString {
            formula.forEach { char ->
                if (char == quote) quote = null
                else if (quote == null && (char == '\'' || char == '"')) quote = char
                append(if (char == ',' && quote == null) ';' else char)
            }
        }
    }
}
