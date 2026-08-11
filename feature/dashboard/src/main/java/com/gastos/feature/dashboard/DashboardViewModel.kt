package com.gastos.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.TransactionCategories
import com.gastos.extension.SafeLog
import com.gastos.repository.CurrencyPreference
import com.gastos.repository.DashboardLayout
import com.gastos.repository.DashboardLayoutPreference
import com.gastos.repository.ExchangeRateProvider
import com.gastos.repository.IncomeRepository
import com.gastos.repository.InvoiceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

private const val TAG = "DashboardVM"

/** Tipo de movimiento analizado en las estadísticas interactivas. */
enum class AnalyticsType { GASTOS, INGRESOS }

/** Mes seleccionado en las estadísticas (month en 1..12). */
data class MonthRef(val year: Int, val month: Int) {
    fun previous(): MonthRef =
        if (month == 1) MonthRef(year - 1, 12) else MonthRef(year, month - 1)

    fun next(): MonthRef =
        if (month == 12) MonthRef(year + 1, 1) else MonthRef(year, month + 1)

    override fun toString(): String = "$year-${month.toString().padStart(2, '0')}"
}

data class DayData(
    val dayLabel: String,
    val gastos: Double,
    val ingresos: Double
)

/** Totales convertidos de un día del mes seleccionado. */
data class CalendarDayData(
    val day: Int,
    val gastos: Double,
    val ingresos: Double,
    val balance: Double,
    val count: Int
)

/** Porción del donut: una categoría con su agregación del mes. */
data class AnalyticsSlice(
    val category: String,
    val total: Double,
    val percentage: Double,
    val count: Int,
    val subcategories: List<SubcategorySlice> = emptyList()
)

/** Subcategoría dentro de una categoría. [subcategory] == null → "Sin subcategoría". */
data class SubcategorySlice(
    val label: String,
    val subcategory: String?,
    val total: Double,
    val percentage: Double,
    val count: Int
)

/** Movimiento individual listado en el drill-down. */
data class AnalyticsMovement(
    val id: Long,
    val fecha: Long,
    val descripcion: String,
    val monto: Double,
    val isExpense: Boolean
)

/** Detalle de la categoría seleccionada en el drill-down. */
data class CategoryDetail(
    val category: String,
    val total: Double,
    val percentage: Double,
    val subcategories: List<SubcategorySlice>
)

data class DashboardUiState(
    val totalGastosMes: Double = 0.0,
    val totalIngresosMes: Double = 0.0,
    val balanceMes: Double = 0.0,
    val totalGastosHoy: Double = 0.0,
    val totalIngresosHoy: Double = 0.0,
    val totalGastosSemana: Double = 0.0,
    val totalIngresosSemana: Double = 0.0,
    val dailyData: List<DayData> = emptyList(),
    val isLoading: Boolean = true,
    val totalFacturas: Int = 0,
    val totalIngresosCount: Int = 0,
    /** Registros del mes que se convirtieron desde una moneda distinta a la
     *  moneda por defecto (para mostrar la desglose de la conversión). */
    val convertedRecords: List<ConvertedRecord> = emptyList(),
    /** Tasa `defaultCurrency → USD` aplicada cuando es != 1.0 (para mostrarla). */
    val defaultToUsdRate: Double? = null,
    /** Moneda por defecto del usuario (para mostrar junto a los registros convertidos). */
    val defaultCurrency: String = "EUR",
    // ---- Estadísticas interactivas ----
    /** Mes seleccionado para el análisis. */
    val selectedMonth: MonthRef = MonthRef(1970, 1),
    /** Etiqueta del mes seleccionado (ej. "Agosto 2026"). */
    val selectedMonthLabel: String = "",
    /** ¿El mes seleccionado es el mes actual? */
    val isCurrentMonth: Boolean = true,
    /** Tipo analizado (Gastos/Ingresos). */
    val analyticsType: AnalyticsType = AnalyticsType.GASTOS,
    /** Total del tipo seleccionado en el mes (moneda por defecto). */
    val analyticsTotal: Double = 0.0,
    /** Porciones del donut, ordenadas por importe descendente. */
    val analyticsSlices: List<AnalyticsSlice> = emptyList(),
    /** Categoría seleccionada en el drill-down (null → vista raíz del donut). */
    val selectedCategory: String? = null,
    /** Subcategoría seleccionada en el drill-down (null → lista de subcategorías). */
    val selectedSubcategory: String? = null,
    /** Detalle de [selectedCategory] cuando está seleccionada. */
    val categoryDetail: CategoryDetail? = null,
    /** Movimientos de la categoría+subcategoría seleccionada. */
    val movements: List<AnalyticsMovement> = emptyList(),
    // ---- Calendario financiero ----
    /** Días con movimientos convertibles del mes seleccionado. */
    val calendarDays: List<CalendarDayData> = emptyList(),
    /** Día abierto en la hoja de detalle, o null si no hay hoja. */
    val selectedDay: Int? = null,
    /** Movimientos del día seleccionado. */
    val dayMovements: List<AnalyticsMovement> = emptyList(),
    /** Balance del día seleccionado. */
    val selectedDayBalance: Double = 0.0,
    // ---- Widgets configurables ----
    /** Orden de widgets visibles (IDs estables, ya normalizados). */
    val widgetOrder: List<String> = emptyList(),
    /** Widgets ocultos por el usuario (IDs estables). */
    val hiddenWidgets: Set<String> = emptySet(),
    /** Modo de personalización activo. */
    val isEditMode: Boolean = false,
    /** Widget que se está arrastrando (para realce visual). */
    val draggingWidgetId: String? = null,
    /** Pendiente de mostrar snackbar de "Distribución restablecida" con Deshacer. */
    val showResetUndo: Boolean = false
)

/**
 * Resumen de un registro que se convirtió desde una moneda distinta a la
 * moneda por defecto del usuario (para mostrar el desglose en la UI).
 */
data class ConvertedRecord(
    val descripcion: String,
    val monedaOriginal: String,
    val montoOriginal: Double,
    val montoConvertido: Double,
    /** Tasa usada: 1 unidad de `monedaOriginal` = `rateApplied` unidades de destino. */
    val rateApplied: Double,
    /** Epoch ms de la tasa usada. */
    val asOf: Long?
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val invoiceRepository: InvoiceRepository,
    private val incomeRepository: IncomeRepository,
    private val exchangeRateProvider: ExchangeRateProvider,
    private val currencyPreference: CurrencyPreference,
    private val dashboardLayoutPreference: DashboardLayoutPreference
) : ViewModel() {

    /** Reloj inyectable para pruebas deterministas. */
    internal var nowProvider: () -> Long = System::currentTimeMillis

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val selectedMonth = MutableStateFlow(currentMonth())
    private val analyticsType = MutableStateFlow(AnalyticsType.GASTOS)
    private val selectedCategory = MutableStateFlow<String?>(null)
    private val selectedSubcategory = MutableStateFlow<String?>(null)
    private val selectedCalendarDay = MutableStateFlow<Int?>(null)

    /** Última distribución antes de un reset (para Deshacer). */
    private var lastResetLayout: DashboardLayout? = null

    init {
        observeDashboardData()
        observeLayout()
    }

    /**
     * Agrega facturas + ingresos convertidos a la moneda por defecto del
     * usuario. Recalcula en cuanto cambian los datos, las tasas de cambio,
     * la moneda por defecto o la selección (mes / tipo / drill-down).
     *
     * Los registros cuya moneda no tenga tasa de cambio se EXCLUYEN del
     * total (convert() devuelve null → contribuyen 0).
     */
    private fun observeDashboardData() {
        viewModelScope.launch {
            combine(
                combine(
                    invoiceRepository.getAllInvoices(),
                    incomeRepository.getAllIncomes(),
                    exchangeRateProvider.rates,
                    currencyPreference.defaultCurrency
                ) { invoices, incomes, _, target ->
                    Triple(invoices, incomes, target)
                },
                combine(
                    selectedMonth,
                    analyticsType,
                    selectedCategory,
                    selectedSubcategory,
                    selectedCalendarDay
                ) { month, type, category, subcategory, day ->
                    DashboardSelection(month, type, category, subcategory, day)
                }
            ) { data, selection ->
                computeState(data.first, data.second, data.third, selection)
            }.collect { state ->
                // computeState construye un estado nuevo desde cero; hay que
                // preservar los campos de la distribución de widgets, que
                // viven en su propio flujo persistido.
                _uiState.update { current ->
                    state.copy(
                        widgetOrder = current.widgetOrder,
                        hiddenWidgets = current.hiddenWidgets,
                        isEditMode = current.isEditMode,
                        draggingWidgetId = current.draggingWidgetId,
                        showResetUndo = current.showResetUndo
                    )
                }
            }
        }
    }

    // ---- Widgets configurables ----

    /**
     * Observa la distribución persistida y la normaliza contra el registro
     * de widgets: se descartan IDs desconocidos y se añaden al final los
     * widgets nuevos que lleguen en versiones futuras.
     */
    private fun observeLayout() {
        viewModelScope.launch {
            dashboardLayoutPreference.dashboardLayout
                .map(::normalizeLayout)
                .collect { layout ->
                    _uiState.update {
                        it.copy(
                            widgetOrder = layout.widgetOrder,
                            hiddenWidgets = layout.hiddenWidgets
                        )
                    }
                }
        }
    }

    private fun normalizeLayout(persisted: DashboardLayout): DashboardLayout {
        val known = DashboardWidget.entries.map { it.id }.toSet()
        val validOrder = persisted.widgetOrder.filter { it in known }
        val order = (validOrder + DashboardWidget.defaultOrder).distinct()
        val hidden = persisted.hiddenWidgets.filter { it in known }.toSet()
        return DashboardLayout(order, hidden)
    }

    private fun persistLayout(
        order: List<String>,
        hidden: Set<String>,
        updateState: (DashboardUiState) -> DashboardUiState = { it }
    ) {
        val layout = DashboardLayout(order, hidden)
        _uiState.update { current ->
            updateState(current.copy(widgetOrder = order, hiddenWidgets = hidden))
        }
        viewModelScope.launch {
            dashboardLayoutPreference.updateDashboardLayout(layout)
        }
    }

    fun setEditMode(enabled: Boolean) {
        _uiState.update { it.copy(isEditMode = enabled) }
    }

    fun onDragStart(widgetId: String) {
        _uiState.update { it.copy(draggingWidgetId = widgetId) }
    }

    fun onDragEnd() {
        _uiState.update { it.copy(draggingWidgetId = null) }
    }

    /** Mueve un widget visible de [from] a [to] y persiste. */
    fun moveWidget(from: Int, to: Int) {
        val current = _uiState.value
        val visibleOrder = current.widgetOrder
            .filter { it !in current.hiddenWidgets }
            .toMutableList()
        if (from !in visibleOrder.indices || to !in visibleOrder.indices) return

        val id = visibleOrder.removeAt(from)
        visibleOrder.add(to, id)

        // Conserva las posiciones de los widgets ocultos y reordena solo los
        // elementos que el usuario ve en pantalla.
        var visibleIndex = 0
        val order = current.widgetOrder.map { widgetId ->
            if (widgetId in current.hiddenWidgets) {
                widgetId
            } else {
                visibleOrder[visibleIndex++]
            }
        }
        persistLayout(order, current.hiddenWidgets)
    }

    fun hideWidget(widgetId: String) {
        persistLayout(
            _uiState.value.widgetOrder,
            _uiState.value.hiddenWidgets + widgetId
        )
    }

    fun restoreWidget(widgetId: String) {
        persistLayout(
            _uiState.value.widgetOrder,
            _uiState.value.hiddenWidgets - widgetId
        )
    }

    /** Restaura el orden y visibilidad predeterminados (con Deshacer). */
    fun resetLayout() {
        lastResetLayout = DashboardLayout(
            widgetOrder = _uiState.value.widgetOrder,
            hiddenWidgets = _uiState.value.hiddenWidgets
        )
        persistLayout(DashboardWidget.defaultOrder, emptySet()) {
            it.copy(showResetUndo = true)
        }
    }

    fun undoResetLayout() {
        val previousLayout = lastResetLayout
        lastResetLayout = null
        if (previousLayout != null) {
            persistLayout(previousLayout.widgetOrder, previousLayout.hiddenWidgets) {
                it.copy(showResetUndo = false)
            }
        } else {
            _uiState.update { it.copy(showResetUndo = false) }
        }
    }

    fun dismissResetUndo() {
        lastResetLayout = null
        _uiState.update { it.copy(showResetUndo = false) }
    }

    // ---- Acciones de selección ----

    fun previousMonth() {
        selectedCalendarDay.update { null }
        resetDrillDown()
        selectedMonth.update { it.previous() }
    }

    /** Solo permite navegar hacia el futuro hasta el mes actual. */
    fun nextMonth() {
        selectedCalendarDay.update { null }
        resetDrillDown()
        selectedMonth.update { month ->
            if (month == currentMonth()) month else month.next()
        }
    }

    fun selectMonth(year: Int, month: Int) {
        selectedCalendarDay.update { null }
        resetDrillDown()
        selectedMonth.update { MonthRef(year, month) }
    }

    fun setAnalyticsType(type: AnalyticsType) {
        resetDrillDown()
        analyticsType.update { type }
    }

    /** Abre el drill-down de una categoría. */
    fun selectCategory(category: String) {
        selectedCategory.update { category }
        selectedSubcategory.update { null }
    }

    /** Navega al nivel de subcategoría (label "Sin subcategoría" → null). */
    fun selectSubcategory(subcategory: String?) {
        selectedSubcategory.update { subcategory }
    }

    /** Abre el detalle de un día del calendario financiero. */
    fun selectDay(day: Int) {
        selectedCalendarDay.update { day }
    }

    /** Cierra el detalle del día seleccionado. */
    fun clearSelectedDay() {
        selectedCalendarDay.update { null }
    }

    /** Cierra el drill-down volviendo a la vista raíz. */
    fun resetDrillDown() {
        selectedCategory.update { null }
        selectedSubcategory.update { null }
    }

    private fun currentMonth(): MonthRef {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowProvider()
        return MonthRef(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
    }

    /**
     * Cálculo puro del estado del dashboard a partir de los registros, la
     * moneda destino y la selección actual. Usa [ExchangeRateProvider] para
     * convertir cada importe. Si una moneda no tiene tasa, su importe se
     * excluye (suma 0).
     */
    private fun computeState(
        invoices: List<Invoice>,
        incomes: List<Income>,
        target: String,
        selection: DashboardSelection
    ): DashboardUiState {
        val now = nowProvider()
        val ranges = computeCurrentRanges(now)
        val monthRange = computeMonthRanges(selection.month)

        val monthlyExpenseInvoices = invoices.filter {
            it.tipo == InvoiceType.GASTO && it.fecha in monthRange.first..monthRange.second
        }
        val monthlyIncomes = incomes.filter { it.fecha in monthRange.first..monthRange.second }
        val gastosMes = monthlyExpenseInvoices.sumInvoicesConverted(target)
        val ingresosMes = monthlyIncomes.sumIncomesConverted(target)

        val gastosHoy = invoices
            .filter { it.tipo == InvoiceType.GASTO && it.fecha in ranges.hoyInicio..ranges.hoyFin }
            .sumInvoicesConverted(target)
        val ingresosHoy = incomes
            .filter { it.fecha in ranges.hoyInicio..ranges.hoyFin }
            .sumIncomesConverted(target)

        val gastosSemana = invoices
            .filter { it.tipo == InvoiceType.GASTO && it.fecha >= ranges.semanaInicio && it.fecha <= now }
            .sumInvoicesConverted(target)
        val ingresosSemana = incomes
            .filter { it.fecha >= ranges.semanaInicio && it.fecha <= now }
            .sumIncomesConverted(target)

        // Registros del mes con moneda distinta a la por defecto.
        val converted = mutableListOf<ConvertedRecord>()
        monthlyExpenseInvoices.forEach { inv ->
            val r = exchangeRateProvider.convertWithMeta(inv.total, inv.moneda, target)
            if (r != null && !r.wasNative) {
                converted.add(ConvertedRecord(
                    descripcion = inv.proveedor,
                    monedaOriginal = inv.moneda,
                    montoOriginal = inv.total,
                    montoConvertido = r.amount,
                    rateApplied = r.rateApplied,
                    asOf = r.asOf
                ))
            }
        }
        monthlyIncomes.forEach { inc ->
            val r = exchangeRateProvider.convertWithMeta(inc.monto, inc.moneda, target)
            if (r != null && !r.wasNative) {
                converted.add(ConvertedRecord(
                    descripcion = inc.concepto,
                    monedaOriginal = inc.moneda,
                    montoOriginal = inc.monto,
                    montoConvertido = r.amount,
                    rateApplied = r.rateApplied,
                    asOf = r.asOf
                ))
            }
        }

        val defaultToUsdRate = exchangeRateProvider.convert(1.0, target, "USD")
        val isCurrentMonth = selection.month == currentMonth()

        val analytics = computeAnalytics(
            selection = selection,
            expenseInvoices = monthlyExpenseInvoices,
            incomes = monthlyIncomes,
            target = target
        )
        val calendar = computeCalendar(
            selection = selection,
            expenseInvoices = monthlyExpenseInvoices,
            incomes = monthlyIncomes,
            target = target
        )

        return DashboardUiState(
            totalGastosMes = gastosMes,
            totalIngresosMes = ingresosMes,
            balanceMes = ingresosMes - gastosMes,
            totalGastosHoy = gastosHoy,
            totalIngresosHoy = ingresosHoy,
            totalGastosSemana = gastosSemana,
            totalIngresosSemana = ingresosSemana,
            dailyData = computeDailyData(invoices, incomes, target, now),
            isLoading = false,
            totalFacturas = invoices.count { it.tipo == InvoiceType.GASTO },
            totalIngresosCount = incomes.size,
            convertedRecords = converted,
            defaultToUsdRate = defaultToUsdRate,
            defaultCurrency = target,
            selectedMonth = selection.month,
            selectedMonthLabel = monthLabel(selection.month),
            isCurrentMonth = isCurrentMonth,
            analyticsType = selection.type,
            analyticsTotal = analytics.total,
            analyticsSlices = analytics.slices,
            selectedCategory = selection.category,
            selectedSubcategory = selection.subcategory,
            categoryDetail = analytics.categoryDetail,
            movements = analytics.movements,
            calendarDays = calendar.days,
            selectedDay = selection.day,
            dayMovements = calendar.movements,
            selectedDayBalance = calendar.balance
        )
    }

    // ---- Estadísticas interactivas ----

    private data class AnalyticsResult(
        val total: Double,
        val slices: List<AnalyticsSlice>,
        val categoryDetail: CategoryDetail?,
        val movements: List<AnalyticsMovement>
    )

    private data class CalendarResult(
        val days: List<CalendarDayData>,
        val movements: List<AnalyticsMovement>,
        val balance: Double
    )

    /** Agrega los movimientos convertibles del mes por día para el calendario. */
    private fun computeCalendar(
        selection: DashboardSelection,
        expenseInvoices: List<Invoice>,
        incomes: List<Income>,
        target: String
    ): CalendarResult {
        val records = expenseInvoices.map { invoice ->
            AnalyticsRecord(
                id = invoice.id,
                fecha = invoice.fecha,
                descripcion = invoice.proveedor,
                amount = exchangeRateProvider.convert(invoice.total, invoice.moneda, target),
                category = categoryLabel(invoice.categoria),
                subcategory = invoice.subcategoria,
                isExpense = true
            )
        } + incomes.map { income ->
            AnalyticsRecord(
                id = income.id,
                fecha = income.fecha,
                descripcion = income.concepto,
                amount = exchangeRateProvider.convert(income.monto, income.moneda, target),
                category = categoryLabel(income.categoria),
                subcategory = income.subcategoria,
                isExpense = false
            )
        }

        val convertible = records.filter { it.amount != null }
        val days = convertible
            .groupBy { dayOfMonth(it.fecha) }
            .map { (day, rows) ->
                val gastos = rows.filter { it.isExpense }.sumOf { it.amount!! }
                val ingresos = rows.filterNot { it.isExpense }.sumOf { it.amount!! }
                CalendarDayData(
                    day = day,
                    gastos = gastos,
                    ingresos = ingresos,
                    balance = ingresos - gastos,
                    count = rows.size
                )
            }
            .sortedBy { it.day }

        val selectedRows = selection.day?.let { day ->
            convertible.filter { dayOfMonth(it.fecha) == day }
        }.orEmpty()
        val movements = selectedRows
            .sortedByDescending { it.fecha }
            .map { record ->
                AnalyticsMovement(
                    id = record.id,
                    fecha = record.fecha,
                    descripcion = record.descripcion,
                    monto = record.amount!!,
                    isExpense = record.isExpense
                )
            }
        val balance = selectedRows.sumOf {
            if (it.isExpense) -it.amount!! else it.amount!!
        }

        return CalendarResult(days, movements, balance)
    }

    /**
     * Agrega las estadísticas del mes según la selección:
     *   • slices: total por categoría (para el donut y la leyenda).
     *   • categoryDetail: subcategorías de la categoría seleccionada.
     *   • movements: movimientos de categoría+subcategoría seleccionada.
     */
    private fun computeAnalytics(
        selection: DashboardSelection,
        expenseInvoices: List<Invoice>,
        incomes: List<Income>,
        target: String
    ): AnalyticsResult {
        val isExpense = selection.type == AnalyticsType.GASTOS
        val records: List<AnalyticsRecord> = if (isExpense) {
            expenseInvoices.map { inv ->
                AnalyticsRecord(
                    id = inv.id,
                    fecha = inv.fecha,
                    descripcion = inv.proveedor,
                    amount = exchangeRateProvider.convert(inv.total, inv.moneda, target),
                    category = categoryLabel(inv.categoria),
                    subcategory = inv.subcategoria,
                    isExpense = true
                )
            }
        } else {
            incomes.map { inc ->
                AnalyticsRecord(
                    id = inc.id,
                    fecha = inc.fecha,
                    descripcion = inc.concepto,
                    amount = exchangeRateProvider.convert(inc.monto, inc.moneda, target),
                    category = categoryLabel(inc.categoria),
                    subcategory = inc.subcategoria,
                    isExpense = false
                )
            }
        }

        // Solo cuentan los registros convertibles (con tasa disponible).
        val convertible = records.filter { it.amount != null }
        val total = convertible.sumOf { it.amount!! }

        val groupedByCategory: Map<String, List<AnalyticsRecord>> =
            convertible.groupBy { it.category }

        val slices: List<AnalyticsSlice> = groupedByCategory
            .map { (category, rows) ->
                val categoryTotal = rows.sumOf { it.amount!! }
                val subcategories: List<SubcategorySlice> = rows
                    .groupBy { subcategoryLabel(it.subcategory) to it.subcategory }
                    .map { (labelAndValue, subRows) ->
                        val (label, value) = labelAndValue
                        val subTotal = subRows.sumOf { it.amount!! }
                        SubcategorySlice(
                            label = label,
                            subcategory = value,
                            total = subTotal,
                            percentage = percentage(subTotal, categoryTotal),
                            count = subRows.size
                        )
                    }
                    .sortedByDescending { it.total }
                AnalyticsSlice(
                    category = category,
                    total = categoryTotal,
                    percentage = percentage(categoryTotal, total),
                    count = rows.size,
                    subcategories = subcategories
                )
            }
            .sortedByDescending { it.total }

        val categoryDetail: CategoryDetail? = selection.category?.let { category ->
            slices.firstOrNull { it.category == category }?.let { slice ->
                CategoryDetail(
                    category = slice.category,
                    total = slice.total,
                    percentage = slice.percentage,
                    subcategories = slice.subcategories
                )
            }
        }

        val movements: List<AnalyticsMovement> = if (selection.category != null && selection.subcategory != null) {
            val matching = groupedByCategory[selection.category].orEmpty()
                .filter {
                    normalizeSubcategory(it.subcategory) == normalizeSubcategory(selection.subcategory)
                }
                .sortedByDescending { it.fecha }
            matching.map {
                AnalyticsMovement(
                    id = it.id,
                    fecha = it.fecha,
                    descripcion = it.descripcion,
                    monto = it.amount!!,
                    isExpense = isExpense
                )
            }
        } else {
            emptyList()
        }

        return AnalyticsResult(total, slices, categoryDetail, movements)
    }

    private data class AnalyticsRecord(
        val id: Long,
        val fecha: Long,
        val descripcion: String,
        /** Importe convertido; null = sin tasa disponible (excluido). */
        val amount: Double?,
        val category: String,
        val subcategory: String?,
        val isExpense: Boolean
    )

    private fun percentage(part: Double, total: Double): Double =
        if (total > 0.0) part / total * 100.0 else 0.0

    private fun subcategoryLabel(subcategory: String?): String =
        TransactionCategories.normalizeCategory(subcategory) ?: NO_SUBCATEGORY_LABEL

    private fun normalizeSubcategory(subcategory: String?): String? =
        TransactionCategories.normalizeKey(subcategory)

    /** Suma los importes convertidos a [target] (facturas). */
    private fun List<Invoice>.sumInvoicesConverted(target: String): Double =
        sumOf { exchangeRateProvider.convert(it.total, it.moneda, target) ?: 0.0 }

    /** Suma los importes convertidos a [target] (ingresos). */
    private fun List<Income>.sumIncomesConverted(target: String): Double =
        sumOf { exchangeRateProvider.convert(it.monto, it.moneda, target) ?: 0.0 }

    private fun categoryLabel(category: String?): String =
        TransactionCategories.canonicalExpenseCategory(category)
            ?: TransactionCategories.canonicalIncomeCategory(category)
            ?: TransactionCategories.UNCATEGORIZED_LABEL

    private fun monthLabel(month: MonthRef): String {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(Calendar.YEAR, month.year)
        cal.set(Calendar.MONTH, month.month - 1)
        val raw = SimpleDateFormat("MMMM yyyy", Locale.forLanguageTag("es-ES")).format(cal.time)
        return raw.replaceFirstChar { it.uppercase(Locale.ROOT) }
    }

    private data class CurrentRanges(
        val hoyInicio: Long,
        val hoyFin: Long,
        val semanaInicio: Long
    )

    private fun computeCurrentRanges(now: Long): CurrentRanges {
        val hoyCal = Calendar.getInstance().apply { timeInMillis = now }
        hoyCal.set(Calendar.HOUR_OF_DAY, 0)
        hoyCal.set(Calendar.MINUTE, 0)
        hoyCal.set(Calendar.SECOND, 0)
        hoyCal.set(Calendar.MILLISECOND, 0)
        val hoyInicio = hoyCal.timeInMillis

        hoyCal.set(Calendar.HOUR_OF_DAY, 23)
        hoyCal.set(Calendar.MINUTE, 59)
        hoyCal.set(Calendar.SECOND, 59)
        hoyCal.set(Calendar.MILLISECOND, 999)
        val hoyFin = hoyCal.timeInMillis

        val semanaCal = Calendar.getInstance().apply { timeInMillis = now }
        semanaCal.firstDayOfWeek = Calendar.MONDAY
        semanaCal.set(Calendar.DAY_OF_WEEK, semanaCal.firstDayOfWeek)
        semanaCal.set(Calendar.HOUR_OF_DAY, 0)
        semanaCal.set(Calendar.MINUTE, 0)
        semanaCal.set(Calendar.SECOND, 0)
        semanaCal.set(Calendar.MILLISECOND, 0)
        val semanaInicio = semanaCal.timeInMillis

        return CurrentRanges(hoyInicio, hoyFin, semanaInicio)
    }

    /** [inicio, fin] en epoch ms del mes dado. */
    private fun computeMonthRanges(month: MonthRef): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(Calendar.YEAR, month.year)
        cal.set(Calendar.MONTH, month.month - 1)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val inicio = cal.timeInMillis
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return inicio to cal.timeInMillis
    }

    private fun dayOfMonth(timestamp: Long): Int =
        Calendar.getInstance().apply { timeInMillis = timestamp }
            .get(Calendar.DAY_OF_MONTH)

    private fun computeDailyData(
        invoices: List<Invoice>,
        incomes: List<Income>,
        target: String,
        now: Long
    ): List<DayData> {
        val data = mutableListOf<DayData>()
        val dayFormat = SimpleDateFormat("EEE", Locale.forLanguageTag("es-ES"))

        repeat(7) { i ->
            val dayCal = Calendar.getInstance().apply { timeInMillis = now }
            dayCal.add(Calendar.DAY_OF_YEAR, -(6 - i))
            dayCal.set(Calendar.HOUR_OF_DAY, 0)
            dayCal.set(Calendar.MINUTE, 0)
            dayCal.set(Calendar.SECOND, 0)
            dayCal.set(Calendar.MILLISECOND, 0)
            val dayStart = dayCal.timeInMillis

            dayCal.set(Calendar.HOUR_OF_DAY, 23)
            dayCal.set(Calendar.MINUTE, 59)
            dayCal.set(Calendar.SECOND, 59)
            dayCal.set(Calendar.MILLISECOND, 999)
            val dayEnd = dayCal.timeInMillis

            val gastos = invoices
                .filter { it.tipo == InvoiceType.GASTO && it.fecha >= dayStart && it.fecha <= dayEnd }
                .sumInvoicesConverted(target)
            val ingresos = incomes
                .filter { it.fecha >= dayStart && it.fecha <= dayEnd }
                .sumIncomesConverted(target)

            data.add(
                DayData(
                    dayLabel = dayFormat.format(dayCal.time).take(2).uppercase(),
                    gastos = gastos,
                    ingresos = ingresos
                )
            )
        }

        return data
    }

    /** Fuerza un re-fetch y recálculo. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val invoices = invoiceRepository.getAllInvoices().first()
                val incomes = incomeRepository.getAllIncomes().first()
                val target = currencyPreference.defaultCurrency.value
                val selection = DashboardSelection(
                    month = selectedMonth.value,
                    type = analyticsType.value,
                    category = selectedCategory.value,
                    subcategory = selectedSubcategory.value,
                    day = selectedCalendarDay.value
                )
                val computed = computeState(invoices, incomes, target, selection)
                _uiState.update { current ->
                    computed.copy(
                        widgetOrder = current.widgetOrder,
                        hiddenWidgets = current.hiddenWidgets,
                        isEditMode = current.isEditMode,
                        draggingWidgetId = current.draggingWidgetId,
                        showResetUndo = current.showResetUndo
                    )
                }
            } catch (e: Exception) {
                SafeLog.e(TAG, "Error refrescando dashboard", e)
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private data class DashboardSelection(
        val month: MonthRef,
        val type: AnalyticsType,
        val category: String?,
        val subcategory: String?,
        val day: Int?
    )

    companion object {
        /** Etiqueta para movimientos con categoría pero sin subcategoría. */
        const val NO_SUBCATEGORY_LABEL: String = "Sin subcategoría"
    }
}
