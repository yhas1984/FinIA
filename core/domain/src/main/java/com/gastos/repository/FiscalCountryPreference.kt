package com.gastos.repository

import kotlinx.coroutines.flow.StateFlow

/**
 * País fiscal por defecto del usuario, accesible desde cualquier módulo sin depender de
 * :feature:settings. Igual que [CurrencyPreference], es reactivo: cuando el usuario cambia de país
 * en Ajustes, quien observe este flujo debe recalcular. El informe de IVA lo necesita, porque el
 * país decide el periodo, el plazo y el nombre de la declaración.
 */
interface FiscalCountryPreference {
    /** Código ISO del país fiscal elegido (p. ej. "ES"). */
    val defaultCountry: StateFlow<String>
}
