package com.gastos.domain.model

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Monedas soportadas por FinAI. Lista única compartida por todos los
 * formularios y pantallas para evitar divergencias.
 */
val SUPPORTED_CURRENCIES: List<String> = listOf(
    "EUR", "USD", "MXN", "ARS", "COP", "CLP", "PEN",
    "BOB", "GTQ", "NIO", "PYG", "UYU", "VES"
).let { preferred -> preferred + Currency.getAvailableCurrencies().filter { it.defaultFractionDigits >= 0 }
    .map { it.currencyCode }.filterNot { it in preferred }.sorted() }

private val FISCAL_COUNTRY_NAMES: Map<String, String> = linkedMapOf(
    "ES" to "España",
    "US" to "Estados Unidos",
    "MX" to "México",
    "AR" to "Argentina",
    "CO" to "Colombia",
    "CL" to "Chile",
    "PE" to "Perú",
    "EC" to "Ecuador",
    "BO" to "Bolivia",
    "GT" to "Guatemala",
    "NI" to "Nicaragua",
    "PY" to "Paraguay",
    "UY" to "Uruguay",
    "VE" to "Venezuela"
)

/** País fiscal usado como respaldo cuando un documento no permite detectarlo. */
val SUPPORTED_FISCAL_COUNTRIES: List<String> = FISCAL_COUNTRY_NAMES.keys.toList()

/** Devuelve el nombre del país junto con su código ISO para los selectores. */
fun fiscalCountryLabel(code: String): String {
    val normalizedCode: String = code.uppercase(Locale.ROOT)
    val name: String = FISCAL_COUNTRY_NAMES[normalizedCode] ?: return normalizedCode
    return "$name ($normalizedCode)"
}

/**
 * Moneda de uso habitual en cada país fiscal. Es SOLO una sugerencia para el selector:
 * el país decide las reglas fiscales y la moneda solo cómo se muestran los importes, así que
 * el usuario puede elegir cualquier otra. Venezuela queda fuera a propósito: allí se factura
 * tanto en bolívares como en dólares y ninguna de las dos es «la» habitual.
 */
private val USUAL_CURRENCY_BY_COUNTRY: Map<String, String> = mapOf(
    "ES" to "EUR", "US" to "USD", "MX" to "MXN", "AR" to "ARS", "CO" to "COP",
    "CL" to "CLP", "PE" to "PEN", "EC" to "USD", "BO" to "BOB", "GT" to "GTQ",
    "NI" to "NIO", "PY" to "PYG", "UY" to "UYU"
)

/** Moneda habitual del país, o null si no hay una sugerencia clara. */
fun usualCurrencyForCountry(code: String): String? =
    USUAL_CURRENCY_BY_COUNTRY[code.uppercase(Locale.ROOT)]

/** Devuelve un símbolo legible para el código de moneda. */
fun currencySymbol(code: String): String = when (code.uppercase()) {
    "EUR" -> "€"
    "USD" -> "$"
    "MXN" -> "$"
    "ARS" -> "$"
    "COP" -> "$"
    "CLP" -> "$"
    "PEN" -> "S/"
    "BOB" -> "Bs"
    "GTQ" -> "Q"
    "NIO" -> "C$"
    "PYG" -> "₲"
    "UYU" -> "\$U"
    "VES" -> "Bs"
    else -> code
}

/**
 * Formatea un importe con la moneda indicada.
 * Usa NumberFormat con el símbolo correcto para la moneda,
 * con fallback a "XX,XX COD" si la moneda no es válida en el locale.
 */
fun formatMoney(amount: Double, currencyCode: String, locale: Locale = Locale.forLanguageTag("es-ES")): String {
    return try {
        val fmt = NumberFormat.getCurrencyInstance(locale)
        fmt.currency = Currency.getInstance(currencyCode)
        fmt.format(amount)
    } catch (_: Exception) {
        // La moneda no es reconocida por Java Currency → fallback manual
        val symbol = currencySymbol(currencyCode)
        String.format(locale, "%s%,.2f", symbol, amount)
    }
}
