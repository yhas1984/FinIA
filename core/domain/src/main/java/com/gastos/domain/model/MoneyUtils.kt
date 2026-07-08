package com.gastos.domain.model

/**
 * Parsea un string a Double aceptando tanto coma como punto decimal,
 * para que funcione con teclados es-ES ("21,5") y en-US ("21.5").
 * Devuelve null si el string no es un número válido.
 */
fun String.parseMoney(): Double? {
    val normalized = this.trim().replace(',', '.')
    return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
}
