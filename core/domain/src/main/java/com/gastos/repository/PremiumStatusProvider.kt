package com.gastos.repository

import kotlinx.coroutines.flow.StateFlow

/**
 * Estado de la suscripción Premium, expuesto a través de `core:domain`
 * para que módulos que NO pueden depender de `:feature:settings`
 * (p. ej. `:feature:backup`) puedan limitar funciones de pago sin
 * acoplarse al módulo de ajustes.
 *
 * La implementación real es `BillingManager` (en `:feature:settings`),
 * que persiste el estado en SharedPreferences y lo sincroniza con
 * Google Play Billing.
 */
interface PremiumStatusProvider {
    /** Estado reactivo de Premium. `.value` da la lectura síncrona actual. */
    val isPremium: StateFlow<Boolean>
}
