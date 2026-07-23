package com.gastos.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.extension.SafeLog
import com.gastos.repository.CurrencyPreference
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
    private val incomeRepository: IncomeRepository,
    private val exchangeRateProvider: ExchangeRateProvider,
    private val currencyPreference: CurrencyPreference
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        observeDashboardData()
    }

    /**
     * Agrega facturas + ingresos convertidos a la moneda por defecto del
     * usuario. Recalcula en cuanto cambian los datos, las tasas de cambio
     * o la moneda por defecto.
     *
     * Los registros cuya moneda no tenga tasa de cambio se EXCLUYEN del
     * total (convert() devuelve null → contribuyen 0), en lugar de
     * sumarlos como si estuvieran en la moneda por defecto (que era el bug).
     */
    private fun observeDashboardData() {
        viewModelScope.launch {
            combine(
                invoiceRepository.getAllInvoices(),
                incomeRepository.getAllIncomes(),
                exchangeRateProvider.rates,
                currencyPreference.defaultCurrency
            ) { invoices, incomes, _, target ->
                Triple(invoices, incomes, target)
            }.collect { (invoices, incomes, target) ->
                _uiState.value = computeState(invoices, incomes, target)
            }
        }
    }

    /**
     * Cálculo puro del estado del dashboard a partir de los registros y la
     * moneda destino. Usa [ExchangeRateProvider] para convertir cada importe.
     * Si una moneda no tiene tasa, su importe se excluye (suma 0).
     */
    private fun computeState(
        invoices: List<Invoice>,
        incomes: List<Income>,
        target: String
    ): DashboardUiState {
        val ranges = computeDateRanges()
        val now = System.currentTimeMillis()

        val gastosMes = invoices
            .filter { it.tipo == InvoiceType.GASTO && it.fecha in ranges.mesInicio..ranges.mesFin }
            .sumInvoicesConverted(target)
        val ingresosMes = incomes
            .filter { it.fecha in ranges.mesInicio..ranges.mesFin }
            .sumIncomesConverted(target)

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

        SafeLog.d(TAG, "Dashboard convertido a '$target': gastosMes=$gastosMes, ingresosMes=$ingresosMes")

        return DashboardUiState(
            totalGastosMes = gastosMes,
            totalIngresosMes = ingresosMes,
            balanceMes = ingresosMes - gastosMes,
            totalGastosHoy = gastosHoy,
            totalIngresosHoy = ingresosHoy,
            totalGastosSemana = gastosSemana,
            totalIngresosSemana = ingresosSemana,
            dailyData = computeDailyData(invoices, incomes, target),
            isLoading = false,
            totalFacturas = invoices.count { it.tipo == InvoiceType.GASTO },
            totalIngresosCount = incomes.size
        )
    }

    /** Suma los importes convertidos a [target] (facturas). */
    private fun List<Invoice>.sumInvoicesConverted(target: String): Double =
        sumOf { exchangeRateProvider.convert(it.total, it.moneda, target) ?: 0.0 }

    /** Suma los importes convertidos a [target] (ingresos). */
    private fun List<Income>.sumIncomesConverted(target: String): Double =
        sumOf { exchangeRateProvider.convert(it.monto, it.moneda, target) ?: 0.0 }

    private data class DateRanges(
        val hoyInicio: Long,
        val hoyFin: Long,
        val semanaInicio: Long,
        val mesInicio: Long,
        val mesFin: Long
    )

    private fun computeDateRanges(): DateRanges {
        val hoyCal = Calendar.getInstance()
        hoyCal.set(Calendar.HOUR_OF_DAY, 0)
        hoyCal.set(Calendar.MINUTE, 0)
        hoyCal.set(Calendar.SECOND, 0)
        hoyCal.set(Calendar.MILLISECOND, 0)
        val hoyInicio = hoyCal.timeInMillis

        hoyCal.set(Calendar.HOUR_OF_DAY, 23)
        hoyCal.set(Calendar.MINUTE, 59)
        hoyCal.set(Calendar.SECOND, 59)
        val hoyFin = hoyCal.timeInMillis

        val semanaCal = Calendar.getInstance()
        semanaCal.firstDayOfWeek = Calendar.MONDAY
        semanaCal.set(Calendar.DAY_OF_WEEK, semanaCal.firstDayOfWeek)
        semanaCal.set(Calendar.HOUR_OF_DAY, 0)
        semanaCal.set(Calendar.MINUTE, 0)
        semanaCal.set(Calendar.SECOND, 0)
        semanaCal.set(Calendar.MILLISECOND, 0)
        val semanaInicio = semanaCal.timeInMillis

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
        val mesFin = mesCal.timeInMillis

        return DateRanges(hoyInicio, hoyFin, semanaInicio, mesInicio, mesFin)
    }

    private fun computeDailyData(
        invoices: List<Invoice>,
        incomes: List<Income>,
        target: String
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
                _uiState.value = computeState(invoices, incomes, target)
            } catch (e: Exception) {
                SafeLog.e(TAG, "Error refrescando dashboard", e)
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
}
