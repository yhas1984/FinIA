package com.gastos.extension

import android.util.Log
import com.gastos.common.BuildConfig

/**
 * Wrapper seguro de `android.util.Log` que solo imprime en builds de
 * debug. Evita que datos sensibles (montos, emails, API keys, queries de
 * usuario) se filtren a logcat en producción.
 *
 * NUNCA usar `Log.d/Log.v/Log.i` directamente para datos sensibles.
 * Solo `Log.e/Log.w` para errores sin payload.
 *
 * Uso:
 * ```kotlin
 * SafeLog.d(TAG, "Resultado query=$amount")  // solo debug
 * SafeLog.e(TAG, "Error fetching invoices", throwable)  // producción con throwable
 * ```
 */
object SafeLog {
    /** Misma firma que `Log.d` pero solo imprime si `BuildConfig.DEBUG`. */
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, message)
    }

    /** Misma firma que `Log.v` pero solo imprime si `BuildConfig.DEBUG`. */
    fun v(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.v(tag, message)
    }

    /** Misma firma que `Log.i` pero solo imprime si `BuildConfig.DEBUG`. */
    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.i(tag, message)
    }

    /** Misma firma que `Log.w` (siempre imprime, para errores sin payload). */
    fun w(tag: String, message: String) = Log.w(tag, message)

    /** Misma firma que `Log.w` con throwable. */
    fun w(tag: String, message: String, throwable: Throwable) = Log.w(tag, message, throwable)

    /** Misma firma que `Log.e` (siempre imprime, para errores sin payload). */
    fun e(tag: String, message: String) = Log.e(tag, message)

    /** Misma firma que `Log.e` con throwable. */
    fun e(tag: String, message: String, throwable: Throwable) = Log.e(tag, message, throwable)
}
