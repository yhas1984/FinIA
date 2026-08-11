package com.gastos.repository

import kotlinx.coroutines.flow.Flow

enum class FloatingButtonEdge { LEFT, RIGHT }

data class FloatingButtonPosition(
    val edge: FloatingButtonEdge = FloatingButtonEdge.RIGHT,
    val verticalFraction: Float = 1f
) {
    fun normalized(): FloatingButtonPosition = copy(
        verticalFraction = verticalFraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
    )
}

object FloatingButtonIds {
    const val DASHBOARD_AI = "dashboard_ai"
    const val EXPENSES_ADD = "expenses_add"
    const val INCOMES_ADD = "incomes_add"

    val all: Set<String> = setOf(DASHBOARD_AI, EXPENSES_ADD, INCOMES_ADD)
}

interface FloatingButtonPositionPreference {
    val floatingButtonPositions: Flow<Map<String, FloatingButtonPosition>>

    suspend fun updateFloatingButtonPosition(id: String, position: FloatingButtonPosition)

    suspend fun replaceFloatingButtonPositions(positions: Map<String, FloatingButtonPosition>)
}
