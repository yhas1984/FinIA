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
)

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
fun formatMoney(amount: Double, currencyCode: String): String {
    return try {
        val fmt = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-ES"))
        fmt.currency = Currency.getInstance(currencyCode)
        fmt.format(amount)
    } catch (_: Exception) {
        // La moneda no es reconocida por Java Currency → fallback manual
        val symbol = currencySymbol(currencyCode)
        String.format(Locale.US, "%s%,.2f", symbol, amount)
    }
}
