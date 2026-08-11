package com.gastos

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.gastos.repository.FloatingButtonEdge
import com.gastos.repository.FloatingButtonPosition
import org.junit.Assert.assertEquals
import org.junit.Test

class MovableFloatingActionButtonTest {
    private val bounds = FloatingButtonBounds(left = 16f, right = 300f, top = 80f, bottom = 600f)

    @Test
    fun `positionToOffset respects edge and vertical fraction`() {
        assertEquals(Offset(16f, 80f), positionToOffset(FloatingButtonPosition(FloatingButtonEdge.LEFT, 0f), bounds))
        assertEquals(Offset(300f, 340f), positionToOffset(FloatingButtonPosition(FloatingButtonEdge.RIGHT, 0.5f), bounds))
        assertEquals(Offset(300f, 600f), positionToOffset(FloatingButtonPosition(), bounds))
    }

    @Test
    fun `offsetToPosition clamps and snaps to nearest edge`() {
        assertEquals(
            FloatingButtonPosition(FloatingButtonEdge.LEFT, 0f),
            offsetToPosition(Offset(-100f, -100f), bounds)
        )
        assertEquals(
            FloatingButtonPosition(FloatingButtonEdge.RIGHT, 1f),
            offsetToPosition(Offset(1_000f, 1_000f), bounds)
        )
    }

    @Test
    fun `invalid fractions normalize to safe default`() {
        assertEquals(1f, FloatingButtonPosition(verticalFraction = Float.NaN).normalized().verticalFraction)
        assertEquals(0f, FloatingButtonPosition(verticalFraction = -1f).normalized().verticalFraction)
        assertEquals(1f, FloatingButtonPosition(verticalFraction = 2f).normalized().verticalFraction)
    }

    @Test
    fun `bounds include side insets gutters top guard and bottom navigation`() {
        assertEquals(
            FloatingButtonBounds(left = 36f, right = 844f, top = 120f, bottom = 730f),
            calculateFloatingButtonBounds(
                containerSize = IntSize(1_000, 1_000),
                buttonSize = IntSize(100, 100),
                gutterPx = 16f,
                topGuardPx = 120f,
                bottomPaddingPx = 154f,
                safeLeftPx = 20f,
                safeRightPx = 40f
            )
        )
    }
}
