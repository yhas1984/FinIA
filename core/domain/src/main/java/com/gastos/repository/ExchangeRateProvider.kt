package com.gastos.repository

import kotlinx.coroutines.flow.StateFlow

/**
 * Proveedor de tasas de cambio para convertir importes entre monedas.
 *
 * Las tasas se normalizan contra **USD** (1 USD = `rate` unidades de la
 * moneda), igual que las APIs públicas gratuitas basadas en USD, y se
 * cachean en disco (DataStore) para funcionar sin red.
 *
 * Conversión: `convert(amount, from, to) = amount * rate(to) / rate(from)`.
 */
interface ExchangeRateProvider {

    /** Moneda (código ISO) → tasa frente a USD (1 USD = N unidades). USD=1.0. */
    val rates: StateFlow<Map<String, Double>>

    /** Instante (epoch ms) de la última actualización de las tasas, o null. */
    val lastUpdated: StateFlow<Long?>

    /** Descarga las tasas de la API y las cachea. */
    suspend fun refresh()

    /**
     * Convierte [amount] de [from] a [to].
     * - Si `from == to`, devuelve [amount] sin requerir tasas.
     * - Si falta la tasa de alguna de las dos monedas, devuelve `null`
     *   (el llamador debe EXCLUIR el registro del total, nunca sumarlo
     *   como si fuera la moneda destino: eso sería justo el bug que
     *   esta interfaz viene a arreglar).
     */
    fun convert(amount: Double, from: String, to: String): Double?

    /** ¿Hay tasa cacheada para esta moneda? (USD siempre es true.) */
    fun hasRate(currency: String): Boolean
}
