package com.gastos.feature.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLocale
import com.gastos.domain.model.TransactionCategories
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hoja inferior del drill-down: categoría → subcategorías → movimientos.
 * Muestra un breadcrumb y navega un nivel atrás con la flecha o el gesto
 * de retroceso del sistema.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsDrillDownSheet(
    type: AnalyticsType,
    monthLabel: String,
    detail: CategoryDetail,
    selectedSubcategory: String?,
    subcategoryValueToLabel: (String?) -> String,
    movements: List<AnalyticsMovement>,
    fmt: (Double) -> String,
    onBack: () -> Unit,
    onSelectSubcategory: (String?) -> Unit,
    onOpenMovement: (Boolean, Long) -> Unit,
    onDismiss: () -> Unit
) {
    val isMovementLevel = selectedSubcategory != null
    val language = LocalLocale.current.platformLocale.language
    BackHandler(enabled = true) {
        if (isMovementLevel) onBack() else onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            // Breadcrumb con navegación hacia atrás.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { if (isMovementLevel) onBack() else onDismiss() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(if (type == AnalyticsType.GASTOS) R.string.category_gastos_month else R.string.category_ingresos_month, monthLabel),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (isMovementLevel) {
                            "${TransactionCategories.displayCategory(detail.category, language)} › ${TransactionCategories.displayCategory(subcategoryValueToLabel(selectedSubcategory), language)}"
                        } else {
                            TransactionCategories.displayCategory(detail.category, language)
                        },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                    text = stringResource(R.string.percent_format, detail.percentage),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    AutoSizeMonetaryText(
                        text = fmt(detail.total),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        minFontSize = 11.sp,
                        textAlign = TextAlign.End
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (isMovementLevel) {
                MovementList(
                    movements = movements,
                    fmt = fmt,
                    emptyText = stringResource(R.string.no_movements_month),
                    onOpenMovement = onOpenMovement
                )
            } else {
                SubcategoryList(
                    detail = detail,
                    fmt = fmt,
                    onSelectSubcategory = onSelectSubcategory
                )
            }
        }
    }
}

@Composable
private fun SubcategoryList(
    detail: CategoryDetail,
    fmt: (Double) -> String,
    onSelectSubcategory: (String?) -> Unit
) {
    val maxTotal = detail.subcategories.maxOfOrNull { it.total }?.takeIf { it > 0.0 } ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        detail.subcategories.forEach { sub ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSelectSubcategory(sub.subcategory) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (sub.subcategory == null) {
                                MaterialTheme.colorScheme.outline
                            } else {
                                categoryColor(detail.category)
                            }
                        )
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = sub.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                        text = stringResource(R.string.percent_format, sub.percentage),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    // Barra de progreso relativa a la categoría.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth((sub.total / maxTotal).toFloat().coerceIn(0f, 1f))
                                .clip(RoundedCornerShape(999.dp))
                                .background(categoryColor(detail.category))
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.count_movements_format, sub.count, fmt(sub.total)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun MovementList(
    movements: List<AnalyticsMovement>,
    fmt: (Double) -> String,
    emptyText: String = "",
    onOpenMovement: (Boolean, Long) -> Unit
) {
    if (movements.isEmpty()) {
        Text(
            text = emptyText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
            textAlign = TextAlign.Center
        )
        return
    }
    val dateFormat = SimpleDateFormat("dd/MM", Locale.forLanguageTag("es-ES"))
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(movements, key = { "${if (it.isExpense) "expense" else "income"}:${it.id}" }) { movement ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onOpenMovement(movement.isExpense, movement.id) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = dateFormat.format(Date(movement.fecha)),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        maxLines = 1
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                text = movement.descripcion.ifBlank { stringResource(R.string.showing_no_description) },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(12.dp))
                AutoSizeMonetaryText(
                    text = fmt(movement.monto),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = if (movement.isExpense) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.secondary
                        }
                    ),
                    minFontSize = 11.sp,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}
