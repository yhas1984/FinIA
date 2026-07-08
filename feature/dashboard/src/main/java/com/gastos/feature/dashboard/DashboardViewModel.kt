package com.gastos.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.repository.IncomeRepository
import com.gastos.repository.InvoiceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import android.util.Log

private const val TAG = "DashboardVM"

data class DayData(
    val dayLabel: String,
    val gastos: Double,
    val ingresos: Double
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
    val totalIngresosCount: Int = 0
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val invoiceRepository: InvoiceRepository,
    private val incomeRepository: IncomeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        observeDashboardData()
    }

    private fun observeDashboardData() {
        viewModelScope.launch {
            combine(
                invoiceRepository.getAllInvoices(),
                incomeRepository.getAllIncomes()
            ) { invoices, incomes ->
                Pair(invoices, incomes)
            }.collect { (invoices, incomes) ->
                Log.d(TAG, "Dashboard: ${invoices.size} invoices, ${incomes.size} incomes")
                invoices.forEach { inv -> Log.d(TAG, "  Invoice: id=${inv.id}, tipo=${inv.tipo}, fecha=${inv.fecha}, total=${inv.total}") }
                incomes.forEach { inc -> Log.d(TAG, "  Income: id=${inc.id}, fecha=${inc.fecha}, monto=${inc.monto}") }

                val now = System.currentTimeMillis()
                val ranges = computeDateRanges()
                Log.d(TAG, "  Ranges: hoy=${ranges.hoyInicio}-${ranges.hoyFin}, semana=${ranges.semanaInicio}-now, mes=${ranges.mesInicio}-${ranges.mesFin}")

                // Filtrar por rangos
                val gastosMes = invoices
                    .filter { it.tipo == InvoiceType.GASTO && it.fecha >= ranges.mesInicio && it.fecha <= ranges.mesFin }
                    .sumOf { it.total }

                val ingresosMes = incomes
                    .filter { it.fecha >= ranges.mesInicio && it.fecha <= ranges.mesFin }
                    .sumOf { it.monto }

                val gastosHoy = invoices
                    .filter { it.tipo == InvoiceType.GASTO && it.fecha >= ranges.hoyInicio && it.fecha <= ranges.hoyFin }
                    .sumOf { it.total }

                val ingresosHoy = incomes
                    .filter { it.fecha >= ranges.hoyInicio && it.fecha <= ranges.hoyFin }
                    .sumOf { it.monto }

                val gastosSemana = invoices
                    .filter { it.tipo == InvoiceType.GASTO && it.fecha >= ranges.semanaInicio && it.fecha <= now }
                    .sumOf { it.total }

                val ingresosSemana = incomes
                    .filter { it.fecha >= ranges.semanaInicio && it.fecha <= now }
                    .sumOf { it.monto }

                Log.d(TAG, "  Computed: gastosMes=$gastosMes, ingresosMes=$ingresosMes, gastosHoy=$gastosHoy, gastosSemana=$gastosSemana")

                // Datos diarios
                val dailyData = computeDailyData(invoices, incomes)

                _uiState.update {
                    it.copy(
                        totalGastosMes = gastosMes,
                        totalIngresosMes = ingresosMes,
                        balanceMes = ingresosMes - gastosMes,
                        totalGastosHoy = gastosHoy,
                        totalIngresosHoy = ingresosHoy,
                        totalGastosSemana = gastosSemana,
                        totalIngresosSemana = ingresosSemana,
                        dailyData = dailyData,
                        isLoading = false,
                        totalFacturas = invoices.size,
                        totalIngresosCount = incomes.size
                    )
                }
            }
        }
    }

    private data class DateRanges(
        val hoyInicio: Long,
        val hoyFin: Long,
        val semanaInicio: Long,
        val mesInicio: Long,
        val mesFin: Long
    )

    private fun computeDateRanges(): DateRanges {
        // Hoy - crear instancia fresca
        val hoyCal = Calendar.getInstance()
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

        // Esta semana - crear instancia fresca
        val semanaCal = Calendar.getInstance()
        semanaCal.firstDayOfWeek = Calendar.MONDAY
        semanaCal.set(Calendar.DAY_OF_WEEK, semanaCal.firstDayOfWeek)
        semanaCal.set(Calendar.HOUR_OF_DAY, 0)
        semanaCal.set(Calendar.MINUTE, 0)
        semanaCal.set(Calendar.SECOND, 0)
        semanaCal.set(Calendar.MILLISECOND, 0)
        val semanaInicio = semanaCal.timeInMillis

        // Este mes - crear instancia fresca
        val mesCal = Calendar.getInstance()
        mesCal.set(Calendar.DAY_OF_MONTH, 1)
        mesCal.set(Calendar.HOUR_OF_DAY, 0)
        mesCal.set(Calendar.MINUTE, 0)
        mesCal.set(Calendar.SECOND, 0)
        mesCal.set(Calendar.MILLISECOND, 0)
        val mesInicio = mesCal.timeInMillis

        mesCal.set(Calendar.DAY_OF_MONTH, mesCal.getActualMaximum(Calendar.DAY_OF_MONTH))
        mesCal.set(Calendar.HOUR_OF_DAY, 23)
        mesCal.set(Calendar.MINUTE, 59)
        mesCal.set(Calendar.SECOND, 59)
        mesCal.set(Calendar.MILLISECOND, 999)
        val mesFin = mesCal.timeInMillis

        return DateRanges(hoyInicio, hoyFin, semanaInicio, mesInicio, mesFin)
    }

    private fun computeDailyData(
        invoices: List<Invoice>,
        incomes: List<Income>
    ): List<DayData> {
        val data = mutableListOf<DayData>()
        val dayFormat = SimpleDateFormat("EEE", Locale("es", "ES"))

        repeat(7) { i ->
            val dayCal = Calendar.getInstance()
            dayCal.add(Calendar.DAY_OF_YEAR, -(6 - i))
            dayCal.set(Calendar.HOUR_OF_DAY, 0)
            dayCal.set(Calendar.MINUTE, 0)
            dayCal.set(Calendar.SECOND, 0)
            val dayStart = dayCal.timeInMillis

            dayCal.set(Calendar.HOUR_OF_DAY, 23)
            dayCal.set(Calendar.MINUTE, 59)
            dayCal.set(Calendar.SECOND, 59)
            dayCal.set(Calendar.MILLISECOND, 999)
            val dayEnd = dayCal.timeInMillis

            val gastos = invoices
                .filter { it.tipo == InvoiceType.GASTO && it.fecha >= dayStart && it.fecha <= dayEnd }
                .sumOf { it.total }

            val ingresos = incomes
                .filter { it.fecha >= dayStart && it.fecha <= dayEnd }
                .sumOf { it.monto }

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

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            kotlinx.coroutines.delay(300)
        }
    }
}
