package com.gastos.repository

import kotlinx.coroutines.flow.StateFlow

/**
 * Preferencias de moneda del usuario accesibles desde cualquier módulo
 * (sin depender de :feature:settings). Implementado por SettingsRepository.
 */
interface CurrencyPreference {
    /** Moneda por defecto del usuario (p. ej. "EUR"). Se usa como destino
     *  al agregar importes de distintas monedas. */
    val defaultCurrency: StateFlow<String>
}
