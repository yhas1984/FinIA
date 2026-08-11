package com.gastos.feature.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar
import java.util.Locale

private val CALENDAR_WEEKDAYS = listOf("LU", "MA", "MI", "JU", "VI", "SA", "DO")

/** Widget mensual con totales diarios y acceso al detalle de cada día. */
@Composable
fun FinancialCalendarCard(
    month: MonthRef,
    monthLabel: String,
    isCurrentMonth: Boolean,
    days: List<CalendarDayData>,
    selectedDay: Int?,
    todayDay: Int?,
    onDayClick: (Int) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    val dayDataByNumber = days.associateBy { it.day }
    val gridDays = calendarGridDays(month)

    GlassCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Calendario financiero",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = monthLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onPreviousMonth) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Mes anterior"
                )
            }
            IconButton(onClick = onNextMonth, enabled = !isCurrentMonth) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Mes siguiente",
                    tint = if (isCurrentMonth) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            CALENDAR_WEEKDAYS.forEach { weekday ->
                Text(
                    text = weekday,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        gridDays.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    CalendarDayCell(
                        day = day,
                        data = day?.let(dayDataByNumber::get),
                        isSelected = day != null && day == selectedDay,
                        isToday = day != null && day == todayDay,
                        isFuture = day != null && todayDay != null && day > todayDay,
                        onClick = { day?.let(onDayClick) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CalendarLegendDot(color = MaterialTheme.colorScheme.error, label = "Gastos")
            Spacer(modifier = Modifier.width(16.dp))
            CalendarLegendDot(color = MaterialTheme.colorScheme.secondary, label = "Ingresos")
        }
        Text(
            text = "Toca un día para ver sus movimientos",
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RowScope.CalendarDayCell(
    day: Int?,
    data: CalendarDayData?,
    isSelected: Boolean,
    isToday: Boolean,
    isFuture: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    val background = when {
        day == null -> Color.Transparent
        isSelected -> MaterialTheme.colorScheme.primary
        isFuture -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    }
    val contentColor = when {
        day == null -> Color.Transparent
        isSelected -> MaterialTheme.colorScheme.onPrimary
        isFuture -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = Modifier
            .weight(1f)
            .aspectRatio(0.82f)
            .padding(2.dp)
            .clip(shape)
            .background(background)
            .then(
                if (isToday && day != null && !isSelected) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                }
            )
            .clickable(enabled = day != null && !isFuture, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 3.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        if (day != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = day.toString(),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = contentColor
                )
                if (data != null) {
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (data.gastos > 0.0) {
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.error)
                            )
                        }
                        if (data.ingresos > 0.0) {
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.secondary)
                            )
                        }
                    }
                    Text(
                        text = compactBalance(data.balance),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = when {
                            data.balance > 0.0 -> MaterialTheme.colorScheme.secondary
                            data.balance < 0.0 -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarLegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Hoja inferior con totales y movimientos del día seleccionado. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarDayDetailSheet(
    monthLabel: String,
    day: Int,
    dayData: CalendarDayData?,
    movements: List<AnalyticsMovement>,
    balance: Double,
    fmt: (Double) -> String,
    onOpenMovement: (Boolean, Long) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(enabled = true, onBack = onDismiss)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Día $day · $monthLabel",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(16.dp))
            CalendarDayTotals(dayData = dayData, balance = balance, fmt = fmt)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Movimientos",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            MovementList(
                movements = movements,
                fmt = fmt,
                emptyText = "Sin movimientos convertibles este día.",
                onOpenMovement = onOpenMovement
            )
        }
    }
}

@Composable
private fun CalendarDayTotals(
    dayData: CalendarDayData?,
    balance: Double,
    fmt: (Double) -> String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CalendarTotalRow(
            label = "Balance",
            amount = fmt(balance),
            color = if (balance >= 0.0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
        )
        CalendarTotalRow(
            label = "Gastos",
            amount = fmt(dayData?.gastos ?: 0.0),
            color = MaterialTheme.colorScheme.error
        )
        CalendarTotalRow(
            label = "Ingresos",
            amount = fmt(dayData?.ingresos ?: 0.0),
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
private fun CalendarTotalRow(label: String, amount: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AutoSizeMonetaryText(
            text = amount,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            color = color,
            minFontSize = 12.sp,
            textAlign = TextAlign.End
        )
    }
}

internal fun calendarGridDays(month: MonthRef): List<Int?> {
    val firstDay = Calendar.getInstance().apply {
        clear()
        set(month.year, month.month - 1, 1)
    }
    val mondayFirstOffset = (firstDay.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val daysInMonth = firstDay.getActualMaximum(Calendar.DAY_OF_MONTH)
    val leading = List<Int?>(mondayFirstOffset) { null }
    val numbered = (1..daysInMonth).map { it as Int? }
    val cells = leading + numbered
    return cells + List((7 - cells.size % 7) % 7) { null }
}

private fun compactBalance(amount: Double): String {
    val sign = if (amount > 0.0) "+" else if (amount < 0.0) "-" else ""
    val absolute = kotlin.math.abs(amount)
    return if (absolute >= 1000.0) {
        "$sign${"%.1f".format(Locale.ROOT, absolute / 1000.0)}k"
    } else {
        "$sign${"%.0f".format(Locale.ROOT, absolute)}"
    }
}
