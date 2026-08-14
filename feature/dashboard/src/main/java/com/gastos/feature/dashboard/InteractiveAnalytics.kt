package com.gastos.feature.dashboard

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gastos.domain.model.TransactionCategories
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Paleta estable del donut: cada categoría recibe siempre el mismo color,
 * de modo que conserve su identidad entre meses y entre Gastos/Ingresos.
 */
private val AnalyticsPalette = listOf(
    Color(0xFFB388FF), // violeta eléctrico
    Color(0xFF69F0AE), // esmeralda
    Color(0xFFFF8A80), // coral
    Color(0xFF80D8FF), // cian
    Color(0xFFFFD180), // ámbar
    Color(0xFFF48FB1), // rosa
    Color(0xFF80CBC4), // teal
    Color(0xFF9FA8DA), // índigo
    Color(0xFFFFAB91), // naranja
    Color(0xFFA5D6A7), // verde
    Color(0xFFCE93D8), // púrpura
    Color(0xFFFFE082)  // miel
)

private val AnalyticsHeaderStackWidth = 420.dp
private const val AnalyticsHeaderStackFontScale = 1.15f

/** Color estable para una categoría; "Sin categoría" siempre neutral. */
fun categoryColor(category: String): Color {
    if (TransactionCategories.isUncategorized(category)) {
        return Color(0xFF9E9E9E)
    }
    val index = Math.floorMod(category.hashCode(), AnalyticsPalette.size)
    return AnalyticsPalette[index]
}

/**
 * Tarjeta protagonista de estadísticas: toggle Gastos/Ingresos, donut
 * animado con el total del mes en el centro y leyenda interactiva.
 */
@Composable
fun InteractiveAnalyticsCard(
    type: AnalyticsType,
    monthLabel: String,
    total: String,
    slices: List<AnalyticsSlice>,
    emptyMessage: String,
    fmt: (Double) -> String,
    onTypeChange: (AnalyticsType) -> Unit,
    onSliceClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(modifier = modifier) {
        AnalyticsHeader(type = type, onTypeChange = onTypeChange)
        Text(
            text = monthLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.height(20.dp))

        if (slices.isEmpty()) {
            EmptyAnalytics(message = emptyMessage)
            return@GlassCard
        }

        // Donut con total centrado.
        DonutChart(
            slices = slices,
            totalLabel = if (type == AnalyticsType.GASTOS) stringResource(R.string.expenses) else stringResource(R.string.income),
            total = total,
            onSliceClick = onSliceClick,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 20.dp)
        )

        // Leyenda interactiva con color, nombre, porcentaje e importe.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            slices.forEach { slice ->
                AnalyticsLegendRow(
                    slice = slice,
                    fmt = fmt,
                    onClick = { onSliceClick(slice.category) }
                )
            }
        }
    }
}

@Composable
private fun AnalyticsHeader(
    type: AnalyticsType,
    onTypeChange: (AnalyticsType) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val stackHeader: Boolean = shouldStackAnalyticsHeader(maxWidth, LocalDensity.current.fontScale)
        if (stackHeader) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnalyticsHeaderTitle()
                AnalyticsTypeToggle(
                    type = type,
                    onTypeChange = onTypeChange,
                    modifier = Modifier.fillMaxWidth(),
                    fillWidth = true
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AnalyticsHeaderTitle(modifier = Modifier.weight(1f))
                AnalyticsTypeToggle(type = type, onTypeChange = onTypeChange)
            }
        }
    }
}

@Composable
private fun AnalyticsHeaderTitle(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.analytics),
        modifier = modifier,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

internal fun shouldStackAnalyticsHeader(maxWidth: androidx.compose.ui.unit.Dp, fontScale: Float): Boolean =
    maxWidth < AnalyticsHeaderStackWidth || fontScale > AnalyticsHeaderStackFontScale

@Composable
private fun AnalyticsTypeToggle(
    type: AnalyticsType,
    onTypeChange: (AnalyticsType) -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        AnalyticsType.entries.forEachIndexed { index, option ->
            SegmentedButton(
                modifier = if (fillWidth) Modifier.weight(1f) else Modifier,
                selected = type == option,
                onClick = { onTypeChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = AnalyticsType.entries.size),
                label = {
                    Text(
                        text = if (option == AnalyticsType.GASTOS) {
                            stringResource(R.string.expenses)
                        } else {
                            stringResource(R.string.income)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                        textAlign = TextAlign.Center
                    )
                }
            )
        }
    }
}

/** Donut con segmentos separados, tap por segmento y total en el centro. */
@Composable
private fun DonutChart(
    slices: List<AnalyticsSlice>,
    totalLabel: String,
    total: String,
    onSliceClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val strokeWidth = with(LocalDensity.current) { 26.dp.toPx() }
    val size = with(LocalDensity.current) { 190.dp.toPx() }

    // Animación de entrada: 0 → 1 al cambiar el dataset.
    var visible by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(slices) {
        visible = true
    }
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "donutProgress"
    )

    Box(
        modifier = modifier.size(190.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(slices) {
                    detectTapGestures { offset ->
                        val center = Offset(size / 2f, size / 2f)
                        val dx = offset.x - center.x
                        val dy = offset.y - center.y
                        val distance = hypot(dx, dy)
                        val innerRadius = strokeWidth / 2f
                        val outerRadius = size / 2f
                        if (distance in innerRadius..outerRadius) {
                            var angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 90f
                            angle = (angle + 360f) % 360f
                            var acc = 0f
                            slices.forEach { slice ->
                                val sweep = (slice.percentage.toFloat() / 100f) * 360f
                                if (angle >= acc && angle < acc + sweep) {
                                    onSliceClick(slice.category)
                                }
                                acc += sweep
                            }
                        }
                    }
                }
        ) {
            val gapDegrees = 2.5f
            var start = -90f
            slices.forEach { slice ->
                val fullSweep = (slice.percentage.toFloat() / 100f) * 360f
                val sweep = (fullSweep - gapDegrees).coerceAtLeast(0.5f) * progress
                val color = categoryColor(slice.category)
                drawArc(
                    color = color,
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
                    size = Size(size - strokeWidth, size - strokeWidth),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                start += fullSweep
            }
        }

        // Total del mes en el centro.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = totalLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            AutoSizeMonetaryText(
                text = total,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp),
                minFontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AnalyticsLegendRow(
    slice: AnalyticsSlice,
    fmt: (Double) -> String,
    onClick: () -> Unit
) {
    val language = LocalLocale.current.platformLocale.language
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(categoryColor(slice.category))
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = TransactionCategories.displayCategory(slice.category, language),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = stringResource(R.string.percent_format, slice.percentage),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp)
        )
        Text(
            text = fmt(slice.total),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

@Composable
private fun EmptyAnalytics(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "◔",
                style = MaterialTheme.typography.headlineMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(0.85f)
        )
    }
}
