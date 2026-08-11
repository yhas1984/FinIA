package com.gastos.feature.settings

import com.gastos.repository.FloatingButtonEdge
import com.gastos.repository.FloatingButtonIds
import com.gastos.repository.FloatingButtonPosition
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingButtonPositionCodecTest {
    @Test
    fun `codec round trip preserves known positions`() {
        val positions = mapOf(
            FloatingButtonIds.DASHBOARD_AI to FloatingButtonPosition(FloatingButtonEdge.LEFT, 0.25f),
            FloatingButtonIds.INCOMES_ADD to FloatingButtonPosition(FloatingButtonEdge.RIGHT, 0.7f)
        )

        assertEquals(positions, decodeFloatingButtonPositions(encodeFloatingButtonPositions(positions)))
    }

    @Test
    fun `decoder drops malformed unknown and non finite entries`() {
        val decoded = decodeFloatingButtonPositions(
            "unknown|LEFT|0.2;dashboard_ai|INVALID|0.3;expenses_add|LEFT|NaN;incomes_add|RIGHT|2"
        )

        assertEquals(
            mapOf(FloatingButtonIds.INCOMES_ADD to FloatingButtonPosition(FloatingButtonEdge.RIGHT, 1f)),
            decoded
        )
    }
}
