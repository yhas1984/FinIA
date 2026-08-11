package com.gastos.repository

import kotlinx.coroutines.flow.Flow

/** Distribución del Dashboard: widgets visibles en orden y ocultos. */
data class DashboardLayout(
    val widgetOrder: List<String> = emptyList(),
    val hiddenWidgets: Set<String> = emptySet()
)

/**
 * Preferencia persistida de la distribución del Dashboard. Los IDs de
 * widget son estables e inmutables (nunca etiquetas visibles).
 */
interface DashboardLayoutPreference {
    /** Distribución persistida (vacía = usar la predeterminada del registro). */
    val dashboardLayout: Flow<DashboardLayout>

    suspend fun updateDashboardLayout(layout: DashboardLayout)
}
