package com.gastos.feature.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gastos.feature.dashboard.R

/**
 * Registro de widgets configurables del Dashboard.
 *
 * El ID es estable e inmutable (se persiste en backups); la etiqueta solo
 * se usa para mostrar. Al añadir un widget nuevo en el futuro, se coloca
 * al final de las distribuciones personalizadas gracias a la
 * normalización del ViewModel.
 */
enum class DashboardWidget(
    val id: String,
    val defaultOrder: Int,
    val titleRes: Int
) {
    BALANCE("balance", 1, R.string.month_balance),
    CASHFLOW("cashflow", 2, R.string.cashflow),
    ANALYTICS("analytics", 3, R.string.analytics),
    CALENDAR("calendar", 4, R.string.calendar_financial),
    WEEKLY_CHART("weekly_chart", 5, R.string.last_7_days),
    WEEKLY_TOTALS("weekly_totals", 6, R.string.this_week),
    TODAY("today", 7, R.string.today),
    CONVERSION("conversion", 8, R.string.conversion_currency),
    CHAT_CTA("chat_cta", 9, R.string.chat_with_finai);

    companion object {
        /** Orden por defecto del producto. */
        val defaultOrder: List<String> =
            entries.sortedBy { it.defaultOrder }.map { it.id }

        fun fromId(id: String): DashboardWidget? =
            entries.firstOrNull { it.id == id }
    }
}

/**
 * Envoltorio de un widget: asa de arrastre (arrastrar tras mantener
 * pulsado), acciones de edición y realce mientras se arrastra.
 */
@Composable
fun DashboardWidgetContainer(
    widget: DashboardWidget,
    isEditMode: Boolean,
    isDragging: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    modifier: Modifier = Modifier,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onHide: () -> Unit,
    onDragStart: () -> Unit,
    onDragBy: (deltaY: Float) -> Unit,
    onDragEnd: () -> Unit,
    onLongPressEdit: () -> Unit,
    content: @Composable () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var dragStarted by rememberSaveable(widget.id) { mutableStateOf(false) }

    val containerColor by animateColorAsState(
        targetValue = if (isDragging) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        label = "dragContainer"
    )
    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.02f else 1f,
        label = "dragScale"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = if (isDragging) 24f else 0f
            }
            .then(
                if (!isEditMode) {
                    Modifier.pointerInput(widget.id) {
                        detectTapGestures(onLongPress = { onLongPressEdit() })
                    }
                } else {
                    Modifier
                }
            )
    ) {
        // Fondo y borde con esquinas redondeadas DETRÁS del contenido: el
        // contenido nunca se recorta (evita cortar el primer carácter de
        // textos que empiezan junto a la esquina, p. ej. "Balance").
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(22.dp))
                .background(containerColor)
                .then(
                    if (isEditMode) {
                        Modifier.border(
                            width = 1.5.dp,
                            color = if (isDragging) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                            },
                            shape = RoundedCornerShape(22.dp)
                        )
                    } else {
                        Modifier
                    }
                )
        )

        Column(modifier = Modifier.fillMaxWidth()) {
            content()

            if (isEditMode) {
                // Barra de control del widget en modo edición.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, bottom = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(widget.titleRes),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(
                        imageVector = Icons.Filled.ArrowUpward,
                        contentDescription = stringResource(R.string.move_widget_up, stringResource(widget.titleRes)),
                        tint = if (canMoveUp) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        }
                    )
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(
                        imageVector = Icons.Filled.ArrowDownward,
                        contentDescription = stringResource(R.string.move_widget_down, stringResource(widget.titleRes)),
                        tint = if (canMoveDown) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        }
                    )
                }
                IconButton(onClick = onHide) {
                    Icon(
                        imageVector = Icons.Filled.VisibilityOff,
                        contentDescription = stringResource(R.string.hide_widget, stringResource(widget.titleRes)),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Asa de arrastre: mantener pulsado y deslizar.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .pointerInput(widget.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    dragStarted = true
                                    onDragStart()
                                },
                                onDragEnd = {
                                    if (dragStarted) {
                                        dragStarted = false
                                        onDragEnd()
                                    }
                                },
                                onDragCancel = {
                                    if (dragStarted) {
                                        dragStarted = false
                                        onDragEnd()
                                    }
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    if (dragStarted) {
                                        onDragBy(amount.y)
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.DragHandle,
                        contentDescription = stringResource(R.string.drag_widget, stringResource(widget.titleRes)),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            }
        }
    }
}

/**
 * Barra de estado del modo edición: instrucciones, Hecho y Restablecer.
 */
@Composable
fun EditModeToolbar(
    onDone: () -> Unit,
    onReset: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.personalize_dashboard),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.drag_handle_instruction),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onReset) {
            Text(stringResource(R.string.reset_layout))
        }
        Button(onClick = onDone) {
            Text(stringResource(R.string.done))
        }
    }
}

/** Bandeja de widgets ocultos (visible solo en modo edición). */
@Composable
fun HiddenWidgetsTray(
    hidden: List<DashboardWidget>,
    onRestore: (DashboardWidget) -> Unit
) {
    if (hidden.isEmpty()) return
    GlassCard {
        Text(
            text = stringResource(R.string.hidden_widgets),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            hidden.forEach { widget ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.outline)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(widget.titleRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onRestore(widget) }) {
                        Text(stringResource(R.string.show))
                    }
                }
            }
        }
    }
}
