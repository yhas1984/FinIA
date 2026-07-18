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
        val fmt = NumberFormat.getCurrencyInstance(Locale("es", "ES"))
        fmt.currency = Currency.getInstance(currencyCode)
        fmt.format(amount)
    } catch (_: Exception) {
        // La moneda no es reconocida por Java Currency → fallback manual
        val symbol = currencySymbol(currencyCode)
        String.format(Locale.US, "%s%,.2f", symbol, amount)
    }
}
