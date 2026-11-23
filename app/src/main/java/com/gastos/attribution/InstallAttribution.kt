package com.gastos.attribution

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.android.installreferrer.api.ReferrerDetails
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * Atribución de instalación con la API oficial *Play Install Referrer*.
 *
 * Para qué existe: `finkit.eu` enlaza a Play con
 * `&referrer=utm_source%3Dfinkit%26utm_campaign%3D...`. Sin esta clase ese parámetro se pierde en
 * la primera pantalla y la web es un agujero negro: no hay forma de saber cuántas instalaciones
 * vienen de FinKit. Con ella el dato queda en disco **antes** de que exista ningún ViewModel.
 *
 * Reglas de diseño (y el motivo de cada una):
 *  - **Se lee en el `Application`, no en un ViewModel.** Un ViewModel de onboarding puede no
 *    ejecutarse nunca (el usuario entra directo por un intent, restaura un backup, mata la app en
 *    el primer frame). El `Application.onCreate` sí se ejecuta siempre.
 *  - **Persistencia inmediata en `SharedPreferences`.** El valor se guarda en el mismo callback en
 *    que llega; si el proceso muere después, el dato sigue ahí. No se guarda en memoria.
 *  - **Una sola lectura por instalación.** Play entrega el referrer esencialmente una vez; cuando
 *    el estado es [STATUS_OK] no se vuelve a enlazar el servicio (ahorra ~ms en cada arranque).
 *  - **Nunca bloquea ni rompe el arranque.** Todo fallo (sin Play Store, sideload, servicio
 *    ocupado) se anota como estado, no se propaga: la app abre igual.
 *  - **Sin dependencia de Play en tests.** El parseo del referrer es Kotlin puro
 *    ([parseReferrerParams]) y se prueba en la JVM sin emulador.
 *
 * El referrer es atribución **gruesa** de campaña (solo Play, una vez por cuenta, no se puede
 * reasertar). No identifica a una persona ni sustituye a un CRM: mide de dónde vino la instalación.
 */
object InstallAttribution {

    const val PREFS = "finai_attribution"
    private const val TAG = "InstallAttribution"

    /** Aún no hay respuesta de Play (servicio no disponible todavía o primera ejecución). */
    const val STATUS_PENDING = "pending"

    /** Referrer leído. Aunque venga vacío (instalación orgánica) el estado es OK: ya no se repite. */
    const val STATUS_OK = "ok"

    /** Este dispositivo no puede leerlo: sideload, emulador sin Play, o servicio no soportado. */
    const val STATUS_UNAVAILABLE = "unavailable"

    /** Play respondió con error. Se reintenta en el siguiente arranque. */
    const val STATUS_FAILED = "failed"

    private const val KEY_STATUS = "status"
    private const val KEY_REFERRER = "referrer"
    private const val KEY_UTM_SOURCE = "utm_source"
    private const val KEY_UTM_MEDIUM = "utm_medium"
    private const val KEY_UTM_CAMPAIGN = "utm_campaign"
    private const val KEY_UTM_CONTENT = "utm_content"
    private const val KEY_UTM_TERM = "utm_term"
    private const val KEY_CLICK_TS = "referrer_click_ts"
    private const val KEY_INSTALL_BEGIN_TS = "install_begin_ts"
    private const val KEY_CAPTURED_AT = "captured_at"
    private const val KEY_FIRST_RUN_AT = "first_run_at"
    private const val KEY_ATTEMPTS = "attempts"

    private const val MAX_ATTEMPTS = 3

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun status(context: Context): String =
        prefs(context).getString(KEY_STATUS, STATUS_PENDING).orEmpty()

    /** `utm_source` ya leído, o `null` si no había o aún no se ha podido leer. */
    fun source(context: Context): String? = prefs(context).getString(KEY_UTM_SOURCE, null)

    fun campaign(context: Context): String? = prefs(context).getString(KEY_UTM_CAMPAIGN, null)

    fun rawReferrer(context: Context): String? = prefs(context).getString(KEY_REFERRER, null)

    /**
     * Marca la primera ejecución (una sola escritura en toda la vida de la instalación) y lanza la
     * lectura del referrer. Es idempotente: llamarla en cada `onCreate` no duplica trabajo.
     */
    fun capture(context: Context) {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_FIRST_RUN_AT)) {
            prefs.edit().putLong(KEY_FIRST_RUN_AT, System.currentTimeMillis()).apply()
        }
        if (prefs.getString(KEY_STATUS, STATUS_PENDING) == STATUS_OK) return
        if (prefs.getString(KEY_STATUS, STATUS_PENDING) == STATUS_UNAVAILABLE) return
        if (prefs.getInt(KEY_ATTEMPTS, 0) >= MAX_ATTEMPTS) {
            prefs.edit().putString(KEY_STATUS, STATUS_UNAVAILABLE).apply()
            return
        }
        prefs.edit().putInt(KEY_ATTEMPTS, prefs.getInt(KEY_ATTEMPTS, 0) + 1).apply()

        // El binding del servicio se hace en el hilo principal y después del primer frame: no debe
        // costar milisegundos de arranque en frío (medido 237 ms antes de este cambio).
        Handler(Looper.getMainLooper()).post { bind(context.applicationContext) }
    }

    private fun bind(appContext: Context) {
        val client = try {
            InstallReferrerClient.newBuilder(appContext).build()
        } catch (t: Throwable) {
            Log.w(TAG, "InstallReferrerClient no disponible", t)
            mark(appContext, STATUS_UNAVAILABLE)
            return
        }

        try {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    when (responseCode) {
                        InstallReferrerClient.InstallReferrerResponse.OK -> {
                            val details: ReferrerDetails? = runCatching { client.installReferrer }.getOrNull()
                            persist(appContext, details)
                            mark(appContext, STATUS_OK)
                        }
                        InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED -> {
                            // Sideload / Play Store antiguo: aquí no hay nada que medir.
                            mark(appContext, STATUS_UNAVAILABLE)
                        }
                        InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE -> {
                            // Normal en los primeros segundos tras instalar: se reintenta el
                            // siguiente arranque (hasta MAX_ATTEMPTS) sin molestar al usuario.
                            mark(appContext, STATUS_PENDING)
                        }
                        else -> mark(appContext, STATUS_FAILED)
                    }
                    runCatching { client.endConnection() }
                }

                override fun onInstallReferrerServiceDisconnected() {
                    mark(appContext, STATUS_PENDING)
                    runCatching { client.endConnection() }
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "startConnection falló", t)
            mark(appContext, STATUS_FAILED)
        }
    }

    private fun persist(context: Context, details: ReferrerDetails?) {
        if (details == null) return
        val raw = runCatching { details.installReferrer }.getOrNull()
        val params = parseReferrerParams(raw)
        val edit = prefs(context).edit()
            .putString(KEY_REFERRER, raw.orEmpty())
            .putLong(KEY_CAPTURED_AT, System.currentTimeMillis())
        params["utm_source"]?.let { edit.putString(KEY_UTM_SOURCE, it) }
        params["utm_medium"]?.let { edit.putString(KEY_UTM_MEDIUM, it) }
        params["utm_campaign"]?.let { edit.putString(KEY_UTM_CAMPAIGN, it) }
        params["utm_content"]?.let { edit.putString(KEY_UTM_CONTENT, it) }
        params["utm_term"]?.let { edit.putString(KEY_UTM_TERM, it) }
        runCatching { details.referrerClickTimestampSeconds }.getOrNull()
            ?.let { edit.putLong(KEY_CLICK_TS, it) }
        runCatching { details.installBeginTimestampSeconds }.getOrNull()
            ?.let { edit.putLong(KEY_INSTALL_BEGIN_TS, it) }
        edit.apply()
        Log.i(TAG, "referrer leído: source=${params["utm_source"]} campaign=${params["utm_campaign"]}")
    }

    private fun mark(context: Context, status: String) {
        prefs(context).edit().putString(KEY_STATUS, status).apply()
    }

    /**
     * Pinta un resumen de una sola línea para el diagnóstico (y para una futura pantalla de
     * «de dónde vino esta instalación»). No inventa: si no hay dato, lo dice.
     */
    fun summary(context: Context): String {
        val p = prefs(context)
        return when (val s = status(context)) {
            STATUS_OK -> {
                val src = p.getString(KEY_UTM_SOURCE, null)
                val camp = p.getString(KEY_UTM_CAMPAIGN, null)
                if (src.isNullOrBlank()) "orgánica" else listOfNotNull(src, camp).joinToString(" / ")
            }
            STATUS_PENDING -> "pendiente"
            STATUS_UNAVAILABLE -> "no disponible"
            STATUS_FAILED -> "error"
            else -> s
        }
    }

    /**
     * Id estable y anónimo de instalación (para deduplicar eventos sin guardar datos personales).
     * Se deriva de un valor aleatorio guardado la primera vez, no de ANDROID_ID ni de publicidad.
     */
    fun anonymousInstallId(context: Context): String {
        val p = prefs(context)
        p.getString("install_id", null)?.let { return it }
        val seed = "${System.currentTimeMillis()}-${System.nanoTime()}-${Math.random()}"
        val id = MessageDigest.getInstance("SHA-256")
            .digest(seed.toByteArray())
            .take(16)
            .joinToString("") { "%02x".format(it) }
        p.edit().putString("install_id", id).apply()
        return id
    }

    /**
     * Convierte el `referrer` crudo de Play (`utm_source%3Dfinkit%26utm_campaign%3D130`) en un mapa.
     * Kotlin puro a propósito: se prueba en la JVM, sin Robolectric ni dispositivo.
     */
    fun parseReferrerParams(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        // Play entrega el referrer ya decodificado (`utm_source=finkit&utm_campaign=...`).
        // Si llega tal cual sale del enlace (`utm_source%3D...%26...`), se decodifica una vez
        // para no perder la atribución por un nivel extra de encoding.
        val normalized: String = if (!raw.contains('=')) {
            runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        } else raw
        return normalized.split('&')
            .mapNotNull { pair ->
                if (pair.isBlank()) return@mapNotNull null
                val separator = pair.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = pair.substring(0, separator).trim()
                val value = runCatching {
                    URLDecoder.decode(pair.substring(separator + 1).trim(), "UTF-8")
                }.getOrDefault(pair.substring(separator + 1).trim())
                if (key.isEmpty() || key.startsWith("$")) null else key to value
            }
            .toMap()
    }
}
