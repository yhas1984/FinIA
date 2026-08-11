package com.gastos

import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gastos.repository.FloatingButtonEdge
import com.gastos.repository.FloatingButtonPosition
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal data class FloatingButtonBounds(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float
) {
    fun clamp(offset: Offset): Offset = Offset(
        x = offset.x.coerceIn(left, right),
        y = offset.y.coerceIn(top, bottom)
    )
}

internal fun positionToOffset(
    position: FloatingButtonPosition,
    bounds: FloatingButtonBounds
): Offset {
    val normalized = position.normalized()
    val yRange = (bounds.bottom - bounds.top).coerceAtLeast(0f)
    return Offset(
        x = if (normalized.edge == FloatingButtonEdge.LEFT) bounds.left else bounds.right,
        y = bounds.top + yRange * normalized.verticalFraction
    )
}

internal fun offsetToPosition(
    offset: Offset,
    bounds: FloatingButtonBounds
): FloatingButtonPosition {
    val clamped = bounds.clamp(offset)
    val edge = if (
        clamped.x <= (bounds.left + bounds.right) / 2f
    ) FloatingButtonEdge.LEFT else FloatingButtonEdge.RIGHT
    val yRange = bounds.bottom - bounds.top
    val fraction = if (yRange > 0f) (clamped.y - bounds.top) / yRange else 1f
    return FloatingButtonPosition(edge, fraction).normalized()
}

internal fun calculateFloatingButtonBounds(
    containerSize: IntSize,
    buttonSize: IntSize,
    gutterPx: Float,
    topGuardPx: Float,
    bottomPaddingPx: Float,
    safeLeftPx: Float,
    safeRightPx: Float
): FloatingButtonBounds {
    val left = safeLeftPx + gutterPx
    val right = containerSize.width - buttonSize.width - safeRightPx - gutterPx
    val bottom = containerSize.height - buttonSize.height - bottomPaddingPx - gutterPx
    return FloatingButtonBounds(
        left = left,
        right = right.coerceAtLeast(left),
        top = topGuardPx,
        bottom = bottom.coerceAtLeast(topGuardPx)
    )
}

@Composable
internal fun MovableFloatingActionButton(
    id: String,
    position: FloatingButtonPosition,
    bottomContentPadding: Dp,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    onPositionChanged: (FloatingButtonPosition) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val layoutDirection = LocalLayoutDirection.current
    val safeTopPx = WindowInsets.safeDrawing.getTop(density).toFloat()
    val safeLeftPx = WindowInsets.safeDrawing.getLeft(density, layoutDirection).toFloat()
    val safeRightPx = WindowInsets.safeDrawing.getRight(density, layoutDirection).toFloat()
    val gutterPx = with(density) { 16.dp.toPx() }
    val topGuardPx = safeTopPx + with(density) { 64.dp.toPx() } + gutterPx
    val bottomPaddingPx = with(density) { bottomContentPadding.toPx() }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var buttonSize by remember { mutableStateOf(IntSize.Zero) }
    var targetOffset by remember(id) { mutableStateOf(Offset.Zero) }
    var isDragging by remember(id) { mutableStateOf(false) }
    var isSettling by remember(id) { mutableStateOf(false) }
    var isPositionReady by remember(id) { mutableStateOf(false) }
    var pendingPosition by remember(id) { mutableStateOf<FloatingButtonPosition?>(null) }

    val bounds = remember(
        containerSize,
        buttonSize,
        gutterPx,
        topGuardPx,
        bottomPaddingPx,
        safeLeftPx,
        safeRightPx
    ) {
        calculateFloatingButtonBounds(
            containerSize = containerSize,
            buttonSize = buttonSize,
            gutterPx = gutterPx,
            topGuardPx = topGuardPx,
            bottomPaddingPx = bottomPaddingPx,
            safeLeftPx = safeLeftPx,
            safeRightPx = safeRightPx
        )
    }

    LaunchedEffect(id, position, bounds, containerSize, buttonSize, isDragging) {
        if (!isDragging && containerSize != IntSize.Zero && buttonSize != IntSize.Zero) {
            val restoredPosition = position.normalized()
            val pending = pendingPosition
            if (pending != null) {
                if (restoredPosition == pending) pendingPosition = null
            }
            val effectivePosition = pending ?: restoredPosition
            val restoredOffset = positionToOffset(effectivePosition, bounds)
            if (!isPositionReady || (restoredOffset - targetOffset).getDistance() > 1f) {
                isSettling = false
                targetOffset = restoredOffset
            }
            isPositionReady = true
        }
    }

    // Si el repositorio no confirma una escritura (o un reset externo la
    // sustituye), abandonamos la posición pendiente y aplicamos el último
    // valor recibido. En producción el SettingsViewModel actualiza el estado
    // de forma optimista, por lo que este fallback no suele activarse.
    LaunchedEffect(id, pendingPosition, position, bounds) {
        val pending = pendingPosition ?: return@LaunchedEffect
        delay(2_000)
        if (pendingPosition == pending && !isDragging) {
            pendingPosition = null
            isSettling = false
            targetOffset = positionToOffset(position.normalized(), bounds)
        }
    }

    val animatedOffset by animateValueAsState(
        targetValue = targetOffset,
        typeConverter = Offset.VectorConverter,
        animationSpec = if (isDragging || !isSettling) snap() else spring(dampingRatio = 0.72f),
        label = "floatingButtonPosition"
    )
    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.1f else 1f,
        label = "floatingButtonScale"
    )
    val previewEdge = offsetToPosition(targetOffset, bounds).edge
    val edgeStateDescription = stringResource(
        if (previewEdge == FloatingButtonEdge.LEFT) {
            R.string.floating_button_state_left
        } else {
            R.string.floating_button_state_right
        }
    )
    val moveLeftLabel = stringResource(R.string.floating_button_move_left)
    val moveRightLabel = stringResource(R.string.floating_button_move_right)
    val resetPositionLabel = stringResource(R.string.floating_button_reset_position)

    fun snapAndPersist(edge: FloatingButtonEdge? = null) {
        val calculated = offsetToPosition(targetOffset, bounds)
        val snapped = calculated.copy(edge = edge ?: calculated.edge).normalized()
        isSettling = true
        targetOffset = positionToOffset(snapped, bounds)
        pendingPosition = snapped
        onPositionChanged(snapped)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
    ) {
        if (isDragging) {
            Box(
                modifier = Modifier
                    .align(
                        if (previewEdge == FloatingButtonEdge.LEFT) {
                            Alignment.CenterStart
                        } else {
                            Alignment.CenterEnd
                        }
                    )
                    .fillMaxHeight(0.45f)
                    .width(3.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        RoundedCornerShape(99.dp)
                    )
            )
        }

        Surface(
            modifier = Modifier
                .size(56.dp)
                .onSizeChanged { buttonSize = it }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    shadowElevation = if (isDragging) 24f else 8f
                    alpha = if (isPositionReady) 1f else 0f
                }
                .offset {
                    IntOffset(animatedOffset.x.roundToInt(), animatedOffset.y.roundToInt())
                }
                .semantics(mergeDescendants = true) {
                    this.contentDescription = contentDescription
                    role = Role.Button
                    stateDescription = edgeStateDescription
                    onClick(label = contentDescription) {
                        onClick()
                        true
                    }
                    customActions = listOf(
                        CustomAccessibilityAction(moveLeftLabel) {
                            snapAndPersist(FloatingButtonEdge.LEFT)
                            true
                        },
                        CustomAccessibilityAction(moveRightLabel) {
                            snapAndPersist(FloatingButtonEdge.RIGHT)
                            true
                        },
                        CustomAccessibilityAction(resetPositionLabel) {
                            isSettling = true
                            val defaultPosition = FloatingButtonPosition()
                            targetOffset = positionToOffset(defaultPosition, bounds)
                            pendingPosition = defaultPosition
                            onPositionChanged(defaultPosition)
                            true
                        }
                    )
                }
                .focusable()
                .onKeyEvent { event ->
                    val activates = event.type == KeyEventType.KeyUp && event.key in setOf(
                        Key.Enter,
                        Key.NumPadEnter,
                        Key.DirectionCenter,
                        Key.Spacebar
                    )
                    if (activates) {
                        onClick()
                        true
                    } else {
                        false
                    }
                }
                .pointerInput(id, onClick) {
                    detectTapGestures(onTap = { onClick() })
                }
                .pointerInput(id, bounds) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            isDragging = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            targetOffset = bounds.clamp(targetOffset + dragAmount)
                        },
                        onDragEnd = {
                            isDragging = false
                            snapAndPersist()
                        },
                        onDragCancel = {
                            isDragging = false
                            targetOffset = positionToOffset(
                                pendingPosition ?: position.normalized(),
                                bounds
                            )
                        }
                    )
                },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shadowElevation = if (isDragging) 12.dp else 6.dp,
            tonalElevation = 6.dp
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null)
            }
        }
    }
}
