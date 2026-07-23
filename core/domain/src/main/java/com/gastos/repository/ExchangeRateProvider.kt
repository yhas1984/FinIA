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
    fun hasRate(currency: String): Boolean =
        rates.value.containsKey(currency.uppercase())

    /**
     * Resultado de una conversión individual con metadatos para que la UI
     * pueda mostrar "importe convertido · tasa usada · fecha de la tasa".
     */
    data class ConvertResult(
        val amount: Double,
        /** Tasa aplicada: 1 unidad de `from` = rateApplied unidades de `to`. */
        val rateApplied: Double,
        /** Fecha (epoch ms) de la tasa usada, o null si no requirió tasa. */
        val asOf: Long?,
        /** true si el importe era nativo (moneda del registro == destino). */
        val wasNative: Boolean
    )

    /**
     * Convierte [amount] de [from] a [to] devolviendo metadatos.
     * Devuelve `null` si falta la tasa de alguna moneda. Si `from == to`
     * devuelve el importe nativo con `wasNative = true`.
     */
    fun convertWithMeta(amount: Double, from: String, to: String): ConvertResult? {
        if (from.equals(to, ignoreCase = true)) {
            return ConvertResult(amount = amount, rateApplied = 1.0,
                asOf = null, wasNative = true)
        }
        val rates = rates.value
        val rFrom = rates[from.uppercase()] ?: return null
        val rTo = rates[to.uppercase()] ?: return null
        return ConvertResult(
            amount = amount * rTo / rFrom,
            rateApplied = rTo / rFrom,
            asOf = lastUpdated.value,
            wasNative = false
        )
    }
}
