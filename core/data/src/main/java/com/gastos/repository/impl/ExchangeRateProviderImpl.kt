package com.gastos.repository.impl

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gastos.extension.SafeLog
import com.gastos.repository.ExchangeRateProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private val Context.ratesDataStore: DataStore<Preferences> by preferencesDataStore(name = "finai_rates")

/**
 * Implementación de [ExchangeRateProvider] con caché en DataStore y descarga
 * desde una API pública gratuita basada en USD (open.er-api.com, sin clave).
 *
 * - Las tasas se cachean en disco; al construirse, carga el caché y, si está
 *   vacío o es antiguo (> 24 h), dispara un refresco en background.
 * - Sin red o si la API falla, se queda con el último caché válido.
 */
@Singleton
class ExchangeRateProviderImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : ExchangeRateProvider {

    companion object {
        private const val TAG = "ExchangeRates"
        private const val API_URL = "https://open.er-api.com/v6/latest/USD"
        private const val MAX_AGE_MS = 24L * 60 * 60 * 1000 // 24 h
        private const val USD = "USD"

        private val KEY_RATES = stringPreferencesKey("rates_json")
        private val KEY_AS_OF = longPreferencesKey("rates_as_of")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _rates = MutableStateFlow<Map<String, Double>>(mapOf(USD to 1.0))
    override val rates: StateFlow<Map<String, Double>> = _rates.asStateFlow()

    private val _lastUpdated = MutableStateFlow<Long?>(null)
    override val lastUpdated: StateFlow<Long?> = _lastUpdated.asStateFlow()

    init {
        // Carga el caché y refresca si hace falta (sin bloquear el constructor).
        scope.launch {
            loadFromCache()
            val asOf = _lastUpdated.value
            val stale = asOf == null || System.currentTimeMillis() - asOf > MAX_AGE_MS
            if (stale) {
                runCatching { refresh() }
            }
        }
    }

    override fun convert(amount: Double, from: String, to: String): Double? {
        if (from.equals(to, ignoreCase = true)) return amount
        val rates = _rates.value
        val rFrom = rates[from.uppercase()] ?: return null
        val rTo = rates[to.uppercase()] ?: return null
        // amount * tasa(to) / tasa(from)   (ver doc de la interfaz)
        return amount * rTo / rFrom
    }

    override fun hasRate(currency: String): Boolean =
        _rates.value.containsKey(currency.uppercase())

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        runCatching {
            val (parsed, asOf) = fetchRates()
            persist(parsed, asOf)
            _rates.value = parsed + (USD to 1.0)
            _lastUpdated.value = asOf
            SafeLog.d(TAG, "Refrescadas ${parsed.size} tasas (asOf=$asOf)")
        }.onFailure { e ->
            SafeLog.w(TAG, "No se pudo refrescar tasas (se conserva caché): ${e.message}")
        }
        Unit
    }

    /** GET a la API y parseo del objeto `rates`. Devuelve (mapa, epochMs). */
    private fun fetchRates(): Pair<Map<String, Double>, Long> {
        val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
        }
        conn.inputStream.use { input ->
            val body = input.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (json.optString("result", "error") != "success") {
                throw IllegalStateException("API devolvió result!=success")
            }
            val ratesObj = json.getJSONObject("rates")
            val parsed = HashMap<String, Double>()
            val keys = ratesObj.keys()
            while (keys.hasNext()) {
                val code = keys.next()
                parsed[code.uppercase()] = ratesObj.getDouble(code)
            }
            val asOf = (json.optLong("time_last_update_unix", 0) * 1000L)
                .takeIf { it > 0 } ?: System.currentTimeMillis()
            return parsed to asOf
        }
    }

    private suspend fun loadFromCache() {
        runCatching {
            val prefs = context.ratesDataStore.data.first()
            val json = prefs[KEY_RATES] ?: return@runCatching
            val asOf = prefs[KEY_AS_OF]
            val obj = JSONObject(json)
            val map = HashMap<String, Double>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val code = keys.next()
                map[code.uppercase()] = obj.getDouble(code)
            }
            _rates.value = map + (USD to 1.0)
            _lastUpdated.value = asOf
        }.onFailure { SafeLog.w(TAG, "Lectura de caché de tasas falló: ${it.message}") }
    }

    private suspend fun persist(rates: Map<String, Double>, asOf: Long) {
        val json = JSONObject().apply {
            rates.forEach { (k, v) -> put(k, v) }
        }.toString()
        context.ratesDataStore.edit { prefs ->
            prefs[KEY_RATES] = json
            prefs[KEY_AS_OF] = asOf
        }
    }
}
