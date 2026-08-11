package com.gastos.feature.dashboard

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gastos.domain.model.formatMoney
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import androidx.compose.ui.unit.Dp
import com.gastos.feature.dashboard.R

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel(),
    defaultCurrency: String = "EUR",
    onNavigateToChat: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToBackup: () -> Unit = {},
    onOpenMovement: (isExpense: Boolean, id: Long) -> Unit = { _, _ -> }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val fmt = { amt: Double -> com.gastos.domain.model.formatMoney(amt, defaultCurrency) }
    val appLocale = LocalLocale.current.platformLocale
    val selectedMonthLabel = remember(uiState.selectedMonth, appLocale) {
        Calendar.getInstance(appLocale).apply {
            clear()
            set(Calendar.YEAR, uiState.selectedMonth.year)
            set(Calendar.MONTH, uiState.selectedMonth.month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }.let { calendar ->
            SimpleDateFormat("MMMM yyyy", appLocale)
                .format(calendar.time)
                .replaceFirstChar { it.uppercase(appLocale) }
        }
    }
    var showMonthPicker by rememberSaveable { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    val visibleWidgets = remember(uiState.widgetOrder, uiState.hiddenWidgets) {
        uiState.widgetOrder.filter { it !in uiState.hiddenWidgets }
    }

    val hiddenWidgetsList = remember(uiState.hiddenWidgets) {
        DashboardWidget.entries
            .filter { it.id in uiState.hiddenWidgets }
            .sortedBy { it.defaultOrder }
    }

    // Acumulador de arrastre; el cruce de posición se decide con la altura
    // real del widget que se está arrastrando (listState).
    var dragAccumulated by remember { mutableStateOf(0f) }
    val handleDrag by rememberUpdatedState<(String, Float) -> Unit> { widgetId, deltaY ->
        dragAccumulated += deltaY
        val current = visibleWidgets.indexOf(widgetId)
        val draggedHeight = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == widgetId }?.size?.toFloat() ?: 0f
        if (current >= 0 && draggedHeight > 0f && visibleWidgets.size > 1) {
            val direction = if (dragAccumulated > 0f) 1 else -1
            val target = (current + direction).coerceIn(0, visibleWidgets.lastIndex)
            if (target == current) {
                dragAccumulated = 0f
            } else {
                val neighborHeight = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.key == visibleWidgets[target] }
                    ?.size
                    ?.toFloat()
                    ?: draggedHeight
                // Cruzar la mitad de la distancia entre ambos widgets basta;
                // no obligamos a arrastrar todo el calendario, que es alto.
                val threshold = ((draggedHeight + neighborHeight) / 2f).coerceAtLeast(1f)
                if (abs(dragAccumulated) >= threshold) {
                    viewModel.moveWidget(current, target)
                    dragAccumulated -= direction * threshold
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            stickyHeader {
                MonthSelectorRow(
                    label = selectedMonthLabel,
                    isCurrentMonth = uiState.isCurrentMonth,
                    onPrevious = viewModel::previousMonth,
                    onNext = viewModel::nextMonth,
                    onPick = { showMonthPicker = true },
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.dashboard_header),
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = onNavigateToBackup) {
                            Icon(
                                Icons.Filled.CloudUpload,
                                contentDescription = stringResource(R.string.backup),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.settings),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { viewModel.setEditMode(!uiState.isEditMode) }
                        ) {
                            Icon(
                                Icons.Filled.Tune,
                                contentDescription = if (uiState.isEditMode) {
                                    stringResource(R.string.finish_customization)
                                } else {
                                    stringResource(R.string.customize_dashboard)
                                },
                                tint = if (uiState.isEditMode) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }

            if (uiState.isEditMode) {
                item {
                    EditModeToolbar(
                        onDone = { viewModel.setEditMode(false) },
                        onReset = viewModel::resetLayout
                    )
                }
            }

            items(
                count = visibleWidgets.size,
                key = { visibleWidgets[it] }
            ) { index ->
                val widgetId = visibleWidgets[index]
                val widget = DashboardWidget.fromId(widgetId) ?: return@items
                DashboardWidgetContainer(
                    widget = widget,
                    isEditMode = uiState.isEditMode,
                    isDragging = uiState.draggingWidgetId == widgetId,
                    canMoveUp = index > 0,
                    canMoveDown = index < visibleWidgets.lastIndex,
                    onMoveUp = { viewModel.moveWidget(index, index - 1) },
                    onMoveDown = { viewModel.moveWidget(index, index + 1) },
                    onHide = { viewModel.hideWidget(widgetId) },
                    onDragStart = {
                        dragAccumulated = 0f
                        viewModel.onDragStart(widgetId)
                    },
                    onDragBy = { deltaY -> handleDrag(widgetId, deltaY) },
                    onDragEnd = {
                        dragAccumulated = 0f
                        viewModel.onDragEnd()
                    },
                    onLongPressEdit = { viewModel.setEditMode(true) },
                    modifier = Modifier.animateItem()
                ) {
                    DashboardWidgetContent(
                        widgetId = widgetId,
                        uiState = uiState,
                        fmt = fmt,
                        monthLabel = selectedMonthLabel,
                        appLocale = appLocale,
                        isEditMode = uiState.isEditMode,
                        onNavigateToChat = onNavigateToChat,
                        onTypeChange = viewModel::setAnalyticsType,
                        onSliceClick = viewModel::selectCategory,
                        onDayClick = viewModel::selectDay,
                        onPreviousMonth = viewModel::previousMonth,
                        onNextMonth = viewModel::nextMonth
                    )
                }
            }

            if (uiState.isEditMode && hiddenWidgetsList.isNotEmpty()) {
                item {
                    HiddenWidgetsTray(
                        hidden = hiddenWidgetsList,
                        onRestore = { viewModel.restoreWidget(it.id) }
                    )
                }
            }

            if (uiState.isLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(80.dp)) // Space for FAB
            }
        }
    }

    // Snackbar de "Distribución restablecida" con Deshacer.
    val distributionRestoredMessage = stringResource(R.string.distribution_restored)
    val undoLabel = stringResource(R.string.undo)
    LaunchedEffect(uiState.showResetUndo, distributionRestoredMessage, undoLabel) {
        if (uiState.showResetUndo) {
            val result = snackbarHostState.showSnackbar(
                message = distributionRestoredMessage,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoResetLayout()
            } else {
                viewModel.dismissResetUndo()
            }
        }
    }

    // Hoja de selección de mes.
    if (showMonthPicker) {
        MonthPickerSheet(
            selectedYear = uiState.selectedMonth.year,
            selectedMonth = uiState.selectedMonth.month,
            onSelect = viewModel::selectMonth,
            onDismiss = { showMonthPicker = false }
        )
    }

    // Drill-down de estadísticas (categoría → subcategoría → movimientos).
    val detail = uiState.categoryDetail
    val noSubcategoryLabel = stringResource(R.string.no_subcategory_label)
    if (uiState.selectedCategory != null && detail != null) {
        AnalyticsDrillDownSheet(
            type = uiState.analyticsType,
            monthLabel = selectedMonthLabel,
            detail = detail,
            selectedSubcategory = uiState.selectedSubcategory,
            subcategoryValueToLabel = { value ->
                value ?: noSubcategoryLabel
            },
            movements = uiState.movements,
            fmt = fmt,
            onBack = {
                if (uiState.selectedSubcategory != null) {
                    viewModel.selectSubcategory(null)
                } else {
                    viewModel.resetDrillDown()
                }
            },
            onSelectSubcategory = viewModel::selectSubcategory,
            onOpenMovement = onOpenMovement,
            onDismiss = viewModel::resetDrillDown
        )
    }

    // Detalle de movimientos del día seleccionado en el calendario.
    uiState.selectedDay?.let { day ->
        CalendarDayDetailSheet(
            monthLabel = selectedMonthLabel,
            day = day,
            dayData = uiState.calendarDays.firstOrNull { it.day == day },
            movements = uiState.dayMovements,
            balance = uiState.selectedDayBalance,
            fmt = fmt,
            onOpenMovement = onOpenMovement,
            onDismiss = viewModel::clearSelectedDay
        )
    }
}

/** Contenido de cada widget según su ID estable. */
@Composable
private fun DashboardWidgetContent(
    widgetId: String,
    uiState: DashboardUiState,
    fmt: (Double) -> String,
    monthLabel: String,
    appLocale: Locale,
    isEditMode: Boolean,
    onNavigateToChat: () -> Unit,
    onTypeChange: (AnalyticsType) -> Unit,
    onSliceClick: (String) -> Unit,
    onDayClick: (Int) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    when (widgetId) {
        DashboardWidget.BALANCE.id -> BalanceWidget(uiState = uiState, fmt = fmt, monthLabel = monthLabel)
        DashboardWidget.CASHFLOW.id -> CashflowCard(
            totalGastos = fmt(uiState.totalGastosMes),
            totalIngresos = fmt(uiState.totalIngresosMes)
        )
        DashboardWidget.ANALYTICS.id -> InteractiveAnalyticsCard(
            type = uiState.analyticsType,
            monthLabel = monthLabel,
            total = fmt(uiState.analyticsTotal),
            slices = uiState.analyticsSlices,
                emptyMessage = if (uiState.analyticsType == AnalyticsType.GASTOS) {
                 stringResource(R.string.no_expense_in_month, monthLabel.lowercase(appLocale))
            } else {
                 stringResource(R.string.no_income_in_month, monthLabel.lowercase(appLocale))
            },
            fmt = fmt,
            onTypeChange = onTypeChange,
            onSliceClick = onSliceClick
        )
        DashboardWidget.CALENDAR.id -> FinancialCalendarCard(
            month = uiState.selectedMonth,
             monthLabel = monthLabel,
            isCurrentMonth = uiState.isCurrentMonth,
            days = uiState.calendarDays,
            selectedDay = uiState.selectedDay,
            todayDay = if (uiState.isCurrentMonth) {
                Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
            } else {
                null
            },
            onDayClick = onDayClick,
            onPreviousMonth = onPreviousMonth,
            onNextMonth = onNextMonth
        )
        DashboardWidget.WEEKLY_CHART.id -> WeeklyChartWidget(uiState = uiState)
        DashboardWidget.WEEKLY_TOTALS.id -> WeeklyTotalsWidget(uiState = uiState, fmt = fmt)
        DashboardWidget.TODAY.id -> TodayWidget(uiState = uiState, fmt = fmt)
        DashboardWidget.CONVERSION.id -> {
            if (isEditMode || uiState.convertedRecords.isNotEmpty()) {
                ConversionWidget(uiState = uiState, fmt = fmt, monthLabel = monthLabel, appLocale = appLocale)
            }
        }
        DashboardWidget.CHAT_CTA.id -> ChatCtaWidget(onNavigateToChat = onNavigateToChat)
    }
}

@Composable
private fun BalanceWidget(
    uiState: DashboardUiState,
    fmt: (Double) -> String,
    monthLabel: String
) {
    Column {
        Text(
            text = stringResource(R.string.balance_for_month, monthLabel),
            style = MaterialTheme.typography.labelMedium.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        AutoSizeMonetaryText(
            text = fmt(uiState.balanceMes),
            style = MaterialTheme.typography.displayLarge.copy(
                fontWeight = FontWeight.Bold,
                color = if (uiState.balanceMes >= 0)
                    MaterialTheme.colorScheme.secondary
                else
                    MaterialTheme.colorScheme.error
            ),
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(),
            minFontSize = 20.sp,
            textAlign = TextAlign.Start
        )
    }
}

@Composable
private fun WeeklyChartWidget(uiState: DashboardUiState) {
    GlassCard {
        Text(
            text = stringResource(R.string.last_7_days),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier.padding(bottom = 12.dp)
        )
        if (uiState.dailyData.isNotEmpty()) {
            WeeklyBarChart(dailyData = uiState.dailyData)
        } else {
            Text(
            text = stringResource(R.string.no_data_this_week),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WeeklyTotalsWidget(
    uiState: DashboardUiState,
    fmt: (Double) -> String
) {
    GlassCard {
        Text(
            text = stringResource(R.string.this_week),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier.padding(bottom = 12.dp)
        )
        TotalsRow(
            label = stringResource(R.string.expenses),
            amount = fmt(uiState.totalGastosSemana),
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(8.dp))
        TotalsRow(
            label = stringResource(R.string.income),
            amount = fmt(uiState.totalIngresosSemana),
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
private fun TodayWidget(
    uiState: DashboardUiState,
    fmt: (Double) -> String
) {
    GlassCard {
        Text(
            text = stringResource(R.string.today),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier.padding(bottom = 12.dp)
        )
        TotalsRow(
            label = stringResource(R.string.expenses),
            amount = fmt(uiState.totalGastosHoy),
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(8.dp))
        TotalsRow(
            label = stringResource(R.string.income),
            amount = fmt(uiState.totalIngresosHoy),
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
private fun TotalsRow(
    label: String,
    amount: String,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AutoSizeMonetaryText(
            text = amount,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            color = color,
            modifier = Modifier.weight(1f),
            minFontSize = 12.sp,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun ConversionWidget(
    uiState: DashboardUiState,
    fmt: (Double) -> String,
    monthLabel: String,
    appLocale: Locale
) {
    if (uiState.convertedRecords.isEmpty()) {
        GlassCard {
            Text(
                text = stringResource(R.string.conversion_currency),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = stringResource(R.string.conversion_no_records, monthLabel.lowercase(appLocale)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    GlassCard {
        val defaultToUsdRate = uiState.defaultToUsdRate
        Text(
            text = stringResource(R.string.conversion_currency),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier.padding(bottom = 12.dp)
        )
        Text(
            text = when {
                uiState.defaultCurrency.equals("USD", ignoreCase = true) ->
                    stringResource(R.string.conversion_rate_usd)
                defaultToUsdRate != null ->
                    stringResource(R.string.conversion_rate, uiState.defaultCurrency, defaultToUsdRate)
                else -> stringResource(R.string.conversion_rate_unavailable, uiState.defaultCurrency)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        uiState.convertedRecords.forEach { rec ->
            ConversionRow(rec = rec, defaultCurrency = uiState.defaultCurrency, fmt = fmt)
        }
    }
}

@Composable
private fun ChatCtaWidget(onNavigateToChat: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onNavigateToChat,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.chat_with_finai),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.chat_with_finai_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.Chat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
fun CashflowCard(totalGastos: String, totalIngresos: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Gastos column
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.expenses),
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    AutoSizeMonetaryText(
                        text = totalGastos,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .fillMaxWidth(),
                        minFontSize = 12.sp,
                        textAlign = TextAlign.Start
                    )
                }
                // Ingresos column
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.income),
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    AutoSizeMonetaryText(
                        text = totalIngresos,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        ),
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .fillMaxWidth(),
                        minFontSize = 12.sp,
                        textAlign = TextAlign.End
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversionRow(
    rec: ConvertedRecord,
    defaultCurrency: String,
    fmt: (Double) -> String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = rec.descripcion,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            AutoSizeMonetaryText(
                text = "${formatMoney(rec.montoOriginal, rec.monedaOriginal)} · 1 ${rec.monedaOriginal} = ${"%.6f".format(rec.rateApplied)} $defaultCurrency",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                minFontSize = 9.sp,
                textAlign = TextAlign.Start
            )
        }
        AutoSizeMonetaryText(
            text = fmt(rec.montoConvertido),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.weight(0.38f),
            minFontSize = 12.sp,
            textAlign = TextAlign.End
        )
    }
}

@Composable
internal fun AutoSizeMonetaryText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color? = null,
    minFontSize: TextUnit,
    textAlign: TextAlign? = null
) {
    val resolvedColor: Color = color
        ?: style.color.takeUnless { it == Color.Unspecified }
        ?: LocalContentColor.current
    BasicText(
        text = text,
        style = style.copy(
            color = resolvedColor,
            textAlign = textAlign ?: style.textAlign
        ),
        modifier = modifier,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        autoSize = TextAutoSize.StepBased(
            maxFontSize = style.fontSize,
            minFontSize = minFontSize,
            stepSize = 1.sp
        )
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            content = content
        )
    }
}

@Composable
fun QuickActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(label)
        }
    }
}

@Composable
fun NeonFab(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(16.dp)
            .size(64.dp)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.secondary
                    )
                ),
                shape = RoundedCornerShape(20.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.new_expense),
                tint = Color.Black,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
fun WeeklyBarChart(dailyData: List<DayData>) {
    val maxValue = (dailyData.maxOfOrNull { maxOf(it.gastos, it.ingresos) } ?: 0.0).coerceAtLeast(1.0)

    Column {
        // Leyenda
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.error, RoundedCornerShape(2.dp))
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.expenses), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(2.dp))
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.income), style = MaterialTheme.typography.labelSmall)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Barras
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom
        ) {
            dailyData.forEach { day ->
                WeeklyBar(
                    dayLabel = day.dayLabel,
                    gastos = day.gastos,
                    ingresos = day.ingresos,
                    maxValue = maxValue,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun WeeklyBar(
    dayLabel: String,
    gastos: Double,
    ingresos: Double,
    maxValue: Double,
    modifier: Modifier = Modifier
) {
    val gastosHeight = ((gastos / maxValue) * 100).coerceAtLeast(4.0).dp
    val ingresosHeight = ((ingresos / maxValue) * 100).coerceAtLeast(4.0).dp

    Column(
        modifier = modifier.padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.height(100.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Center
        ) {
            // Barra de gastos
            AnimatedBar(
                height = gastosHeight,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.width(10.dp)
            )
            Spacer(modifier = Modifier.width(2.dp))
            // Barra de ingresos
            AnimatedBar(
                height = ingresosHeight,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.width(10.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = dayLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun AnimatedBar(
    height: Dp,
    color: Color,
    modifier: Modifier = Modifier
) {
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isVisible = true
    }

    val animatedHeight by animateDpAsState(
        targetValue = if (isVisible) height else 0.dp,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "barHeight"
    )

    Box(
        modifier = modifier
            .width(10.dp)
            .height(animatedHeight)
            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
            .background(color)
    )
}
