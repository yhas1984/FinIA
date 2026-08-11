package com.gastos.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val SPANISH_MONTHS = listOf(
    "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
    "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"
)

/** Selector de mes en formato píldora con navegación prev/sig. */
@Composable
fun MonthSelectorRow(
    label: String,
    isCurrentMonth: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrevious) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Mes anterior",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(999.dp))
                .clickable(onClick = onPick)
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                maxLines = 1
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = "Elegir mes",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        IconButton(onClick = onNext, enabled = !isCurrentMonth) {
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
}

/**
 * Hoja inferior para elegir mes y año. Los meses futuros del año actual
 * aparecen deshabilitados.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthPickerSheet(
    selectedYear: Int,
    selectedMonth: Int,
    onSelect: (year: Int, month: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var year by remember { mutableStateOf(selectedYear) }
    val now = remember { CalendarProvider.today() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Elegir mes",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { year -= 1 },
                    enabled = year > now.first
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Año anterior",
                        tint = if (year > now.first) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        }
                    )
                }
                Text(
                    text = year.toString(),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center
                )
                IconButton(
                    onClick = { year += 1 },
                    enabled = year < now.first
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Año siguiente",
                        tint = if (year < now.first) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                userScrollEnabled = false,
                modifier = Modifier.height(48.dp * 3 + 8.dp * 2)
            ) {
                items(SPANISH_MONTHS) { monthName ->
                    val monthIndex = SPANISH_MONTHS.indexOf(monthName) + 1
                    val isSelected = year == selectedYear && monthIndex == selectedMonth
                    val isFuture = year > now.first || (year == now.first && monthIndex > now.second)
                    MonthCell(
                        label = monthName,
                        isSelected = isSelected,
                        enabled = !isFuture,
                        onClick = {
                            onSelect(year, monthIndex)
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthCell(
    label: String,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val container = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }
    val content = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = content
            ),
            maxLines = 1
        )
    }
}

/** Acceso al calendario para evitar dependencias de tiempo reales en previews. */
internal object CalendarProvider {
    fun today(): Pair<Int, Int> {
        val cal = java.util.Calendar.getInstance()
        return cal.get(java.util.Calendar.YEAR) to (cal.get(java.util.Calendar.MONTH) + 1)
    }
}
