package com.gastos.feature.dashboard

import app.cash.turbine.test
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.repository.CurrencyPreference
import com.gastos.repository.DashboardLayout
import com.gastos.repository.DashboardLayoutPreference
import com.gastos.repository.ExchangeRateProvider
import com.gastos.repository.IncomeRepository
import com.gastos.repository.InvoiceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    /** Mediodía del 11 de agosto de 2026: mes actual = agosto 2026. */
    private val fixedNow: Long = ts(2026, 8, 11, 12)

    private fun ts(year: Int, month: Int, day: Int, hour: Int = 12): Long {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(year, month - 1, day, hour, 0, 0)
        return cal.timeInMillis
    }

    private fun invoice(
        id: Long,
        total: Double,
        fecha: Long,
        categoria: String? = null,
        subcategoria: String? = null,
        moneda: String = "EUR"
    ) = Invoice(
        id = id,
        fecha = fecha,
        proveedor = "Proveedor $id",
        tipo = InvoiceType.GASTO,
        categoria = categoria,
        subcategoria = subcategoria,
        moneda = moneda,
        total = total
    )

    private fun income(
        id: Long,
        monto: Double,
        fecha: Long,
        categoria: String? = null,
        subcategoria: String? = null
    ) = Income(
        id = id,
        fecha = fecha,
        concepto = "Concepto $id",
        monto = monto,
        categoria = categoria,
        subcategoria = subcategoria
    )

    private fun newViewModel(
        invoices: List<Invoice> = emptyList(),
        incomes: List<Income> = emptyList(),
        rates: Map<String, Double> = mapOf("EUR" to 1.0, "USD" to 1.0),
        defaultCurrency: String = "EUR",
        now: Long = fixedNow,
        persistedLayout: DashboardLayout? = null,
        layoutPref: DashboardLayoutPreference? = null,
        dashboardLayoutFlow: MutableStateFlow<DashboardLayout>? = null,
        persistLayoutUpdates: Boolean = false
    ): DashboardViewModel {
        val invoiceRepo = mockk<InvoiceRepository>()
        every { invoiceRepo.getAllInvoices() } returns flowOf(invoices)
        val incomeRepo = mockk<IncomeRepository>()
        every { incomeRepo.getAllIncomes() } returns flowOf(incomes)
        val exchange = mockk<ExchangeRateProvider>()
        every { exchange.rates } returns MutableStateFlow(rates)
        every { exchange.lastUpdated } returns MutableStateFlow(null)
        every { exchange.convert(any(), any(), any()) } answers {
            val amount = firstArg<Double>()
            val from = secondArg<String>().uppercase()
            val to = thirdArg<String>().uppercase()
            val rFrom = rates[from] ?: return@answers null
            val rTo = rates[to] ?: return@answers null
            amount * rTo / rFrom
        }
        every { exchange.convertWithMeta(any(), any(), any()) } answers {
            val amount = firstArg<Double>()
            val from = secondArg<String>().uppercase()
            val to = thirdArg<String>().uppercase()
            if (from == to) {
                ExchangeRateProvider.ConvertResult(amount, 1.0, null, true)
            } else {
                val rFrom = rates[from] ?: return@answers null
                val rTo = rates[to] ?: return@answers null
                ExchangeRateProvider.ConvertResult(
                    amount = amount * rTo / rFrom,
                    rateApplied = rTo / rFrom,
                    asOf = null,
                    wasNative = false
                )
            }
        }
        val currency = mockk<CurrencyPreference>()
        every { currency.defaultCurrency } returns MutableStateFlow(defaultCurrency)
        val layout = layoutPref ?: mockk<DashboardLayoutPreference>(relaxed = true)
        val layoutState = dashboardLayoutFlow ?: MutableStateFlow(persistedLayout ?: DashboardLayout())
        every { layout.dashboardLayout } returns layoutState
        if (persistLayoutUpdates) {
            coEvery { layout.updateDashboardLayout(any()) } coAnswers {
                layoutState.value = firstArg()
            }
        }
        return DashboardViewModel(invoiceRepo, incomeRepo, exchange, currency, layout).apply {
            nowProvider = { now }
        }
    }

    private suspend fun app.cash.turbine.ReceiveTurbine<DashboardUiState>.awaitStable(
        condition: (DashboardUiState) -> Boolean
    ): DashboardUiState {
        var state = awaitItem()
        while (state.isLoading || !condition(state)) {
            state = awaitItem()
        }
        return state
    }

    @Test
    fun `por defecto analiza gastos del mes actual con porcentajes`() = runTest(dispatcher) {
        val vm = newViewModel(
            invoices = listOf(
                invoice(1, 100.0, ts(2026, 8, 5), "Alimentación", "Supermercado"),
                invoice(2, 60.0, ts(2026, 8, 6), "Alimentación", "Restaurantes"),
                invoice(3, 40.0, ts(2026, 8, 7), "Transporte", "Combustible")
            )
        )

        vm.uiState.test {
            val state = awaitStable { it.analyticsSlices.isNotEmpty() }
            assertEquals(200.0, state.analyticsTotal, 0.001)
            assertEquals("Agosto 2026", state.selectedMonthLabel)
            assertEquals(true, state.isCurrentMonth)
            assertEquals(AnalyticsType.GASTOS, state.analyticsType)
            assertEquals(2, state.analyticsSlices.size)
            // Orden descendente por importe.
            assertEquals("Alimentación", state.analyticsSlices[0].category)
            assertEquals(160.0, state.analyticsSlices[0].total, 0.001)
            assertEquals(80.0, state.analyticsSlices[0].percentage, 0.001)
            assertEquals("Transporte", state.analyticsSlices[1].category)
            assertEquals(20.0, state.analyticsSlices[1].percentage, 0.001)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `mes anterior navega y re-agrega solo sus registros`() = runTest(dispatcher) {
        val vm = newViewModel(
            invoices = listOf(
                invoice(1, 100.0, ts(2026, 8, 5), "Alimentación"),
                invoice(2, 300.0, ts(2026, 7, 10), "Vivienda")
            )
        )

        vm.previousMonth()

        vm.uiState.test {
            val state = awaitStable { !it.isCurrentMonth }
            assertEquals(MonthRef(2026, 7), state.selectedMonth)
            assertEquals("Julio 2026", state.selectedMonthLabel)
            assertEquals(false, state.isCurrentMonth)
            assertEquals(300.0, state.analyticsTotal, 0.001)
            assertEquals("Vivienda", state.analyticsSlices.single().category)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `mes siguiente no avanza mas alla del mes actual`() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.nextMonth()

        vm.uiState.test {
            val state = awaitStable { !it.isLoading }
            assertEquals(MonthRef(2026, 8), state.selectedMonth)
            assertEquals(true, state.isCurrentMonth)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `toggle a ingresos analiza los ingresos del mes`() = runTest(dispatcher) {
        val vm = newViewModel(
            invoices = listOf(invoice(1, 100.0, ts(2026, 8, 5), "Alimentación")),
            incomes = listOf(
                income(1, 2000.0, ts(2026, 8, 1), "Nómina", "Salario base"),
                income(2, 500.0, ts(2026, 8, 2), "Ventas", "Productos")
            )
        )

        vm.setAnalyticsType(AnalyticsType.INGRESOS)

        vm.uiState.test {
            val state = awaitStable { it.analyticsType == AnalyticsType.INGRESOS && it.analyticsSlices.isNotEmpty() }
            assertEquals(2500.0, state.analyticsTotal, 0.001)
            assertEquals("Nómina", state.analyticsSlices[0].category)
            assertEquals(80.0, state.analyticsSlices[0].percentage, 0.001)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `drill-down muestra subcategorias y movimientos`() = runTest(dispatcher) {
        val vm = newViewModel(
            invoices = listOf(
                invoice(1, 100.0, ts(2026, 8, 5), "Alimentación", "Supermercado"),
                invoice(2, 60.0, ts(2026, 8, 6), "Alimentación", "Restaurantes"),
                invoice(3, 40.0, ts(2026, 8, 7), "Alimentación")
            )
        )

        vm.uiState.test {
            awaitStable { it.analyticsSlices.isNotEmpty() }
            vm.selectCategory("Alimentación")

            val detailState = awaitStable { it.categoryDetail != null }
            assertEquals("Alimentación", detailState.categoryDetail!!.category)
            assertEquals(200.0, detailState.categoryDetail!!.total, 0.001)
            assertEquals(3, detailState.categoryDetail!!.subcategories.size)
            assertEquals("Supermercado", detailState.categoryDetail!!.subcategories[0].label)
            // La subcategoría nula se agrupa como "Sin subcategoría".
            assertTrue(detailState.categoryDetail!!.subcategories.any { it.subcategory == null })

            vm.selectSubcategory("Supermercado")

            val movementState = awaitStable { it.movements.isNotEmpty() }
            assertEquals(1, movementState.movements.size)
            assertEquals(1L, movementState.movements[0].id)
            assertEquals(true, movementState.movements[0].isExpense)
            assertEquals(100.0, movementState.movements[0].monto, 0.001)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `cambio de tipo resetea el drill-down`() = runTest(dispatcher) {
        val vm = newViewModel(
            invoices = listOf(invoice(1, 100.0, ts(2026, 8, 5), "Alimentación", "Supermercado"))
        )

        vm.uiState.test {
            awaitStable { it.analyticsSlices.isNotEmpty() }
            vm.selectCategory("Alimentación")
            awaitStable { it.selectedCategory != null }

            vm.setAnalyticsType(AnalyticsType.INGRESOS)

            val state = awaitStable { it.analyticsType == AnalyticsType.INGRESOS }
            assertNull(state.selectedCategory)
            assertNull(state.selectedSubcategory)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `registros sin tasa se excluyen de las estadisticas`() = runTest(dispatcher) {
        val vm = newViewModel(
            invoices = listOf(
                invoice(1, 100.0, ts(2026, 8, 5), "Alimentación"),
                invoice(2, 50.0, ts(2026, 8, 6), "Viajes", moneda = "XXX")
            ),
            rates = mapOf("EUR" to 1.0, "USD" to 1.0)
        )

        vm.uiState.test {
            val state = awaitStable { it.analyticsSlices.isNotEmpty() }
            assertEquals(100.0, state.analyticsTotal, 0.001)
            assertEquals(listOf("Alimentación"), state.analyticsSlices.map { it.category })
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `sin datos el donut queda vacio`() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.uiState.test {
            val state = awaitStable { !it.isLoading }
            assertEquals(0.0, state.analyticsTotal, 0.001)
            assertTrue(state.analyticsSlices.isEmpty())
            assertNull(state.categoryDetail)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `balance del mes seleccionado usa sus ingresos y gastos`() = runTest(dispatcher) {
        val vm = newViewModel(
            invoices = listOf(invoice(1, 100.0, ts(2026, 7, 10), "Alimentación")),
            incomes = listOf(income(1, 300.0, ts(2026, 7, 15)))
        )

        vm.previousMonth()

        vm.uiState.test {
            val state = awaitStable { !it.isCurrentMonth }
            assertEquals(100.0, state.totalGastosMes, 0.001)
            assertEquals(300.0, state.totalIngresosMes, 0.001)
            assertEquals(200.0, state.balanceMes, 0.001)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `calendario agrupa gastos e ingresos por dia y abre sus movimientos`() = runTest(dispatcher) {
        val vm = newViewModel(
            invoices = listOf(
                invoice(1, 100.0, ts(2026, 8, 5), "Alimentación"),
                invoice(2, 25.0, ts(2026, 8, 5), "Transporte"),
                invoice(3, 50.0, ts(2026, 8, 7), "Vivienda", moneda = "XXX")
            ),
            incomes = listOf(income(1, 300.0, ts(2026, 8, 5), "Nómina"))
        )

        vm.uiState.test {
            val state = awaitStable { it.calendarDays.isNotEmpty() }
            val dayFive = state.calendarDays.single { it.day == 5 }
            assertEquals(125.0, dayFive.gastos, 0.001)
            assertEquals(300.0, dayFive.ingresos, 0.001)
            assertEquals(175.0, dayFive.balance, 0.001)
            assertEquals(3, dayFive.count)
            assertTrue(state.calendarDays.none { it.day == 7 })

            vm.selectDay(5)
            val detail = awaitStable { it.selectedDay == 5 && it.dayMovements.size == 3 }
            assertEquals(175.0, detail.selectedDayBalance, 0.001)
            assertEquals(false, detail.dayMovements.first { !it.isExpense }.isExpense)
            assertEquals(300.0, detail.dayMovements.first { !it.isExpense }.monto, 0.001)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `cambiar de mes cierra el detalle del calendario`() = runTest(dispatcher) {
        val vm = newViewModel(
            invoices = listOf(invoice(1, 100.0, ts(2026, 8, 5), "Alimentación"))
        )

        vm.uiState.test {
            awaitStable { it.calendarDays.isNotEmpty() }
            vm.selectDay(5)
            awaitStable { it.selectedDay == 5 }

            vm.previousMonth()
            val state = awaitStable { it.selectedMonth == MonthRef(2026, 7) }
            assertNull(state.selectedDay)
            assertTrue(state.calendarDays.isEmpty())
            assertTrue(state.dayMovements.isEmpty())
            cancelAndConsumeRemainingEvents()
        }
    }

    // ---- Widgets configurables ----

    @Test
    fun `orden por defecto coincide con el registro y nada oculto`() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.uiState.test {
            val state = awaitStable { it.widgetOrder.isNotEmpty() }
            assertEquals(DashboardWidget.defaultOrder, state.widgetOrder)
            assertTrue(state.hiddenWidgets.isEmpty())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `distribucion persistida se normaliza descartando ids desconocidos`() = runTest(dispatcher) {
        val persisted = DashboardLayout(
            widgetOrder = listOf("analytics", "desconocido", "balance"),
            hiddenWidgets = setOf("balance", "fantasma")
        )
        val vm = newViewModel(persistedLayout = persisted)

        vm.uiState.test {
            val state = awaitStable { it.widgetOrder.isNotEmpty() }
            // "desconocido" se descarta; "balance" se mantiene oculto; el
            // resto de widgets por defecto se añaden al final.
            assertEquals(
                listOf("analytics", "balance") + DashboardWidget.defaultOrder.filter { it != "analytics" && it != "balance" },
                state.widgetOrder
            )
            assertEquals(setOf("balance"), state.hiddenWidgets)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `mover widget reordena y persiste`() = runTest(dispatcher) {
        val layoutPref = mockk<DashboardLayoutPreference>(relaxed = true)
        val vm = newViewModel(layoutPref = layoutPref)

        vm.uiState.test {
            awaitStable { it.widgetOrder.isNotEmpty() }
            vm.moveWidget(0, 2)

            val state = awaitItem()
            assertEquals("cashflow", state.widgetOrder[0])
            assertEquals("balance", state.widgetOrder[2])
            coVerify { layoutPref.updateDashboardLayout(any()) }
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `mover widget usa indices visibles cuando hay widgets ocultos`() = runTest(dispatcher) {
        val persisted = DashboardLayout(
            widgetOrder = DashboardWidget.defaultOrder,
            hiddenWidgets = setOf(DashboardWidget.CASHFLOW.id)
        )
        val vm = newViewModel(persistedLayout = persisted)

        vm.uiState.test {
            awaitStable { it.widgetOrder.isNotEmpty() }

            // Visible: balance, analytics, calendar, ...; cashflow ocupa un
            // slot persistido pero no debe alterar los índices de pantalla.
            vm.moveWidget(from = 2, to = 0)

            val state = awaitStable { it.widgetOrder.first() == DashboardWidget.CALENDAR.id }
            assertEquals(DashboardWidget.CALENDAR.id, state.widgetOrder[0])
            assertEquals(DashboardWidget.CASHFLOW.id, state.widgetOrder[1])
            assertEquals(DashboardWidget.BALANCE.id, state.widgetOrder[2])
            assertEquals(DashboardWidget.ANALYTICS.id, state.widgetOrder[3])
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `layout reordenado se recupera al recrear el viewmodel`() = runTest(dispatcher) {
        val layoutState = MutableStateFlow(DashboardLayout())
        val layoutPref = mockk<DashboardLayoutPreference>(relaxed = true)
        val firstViewModel = newViewModel(
            layoutPref = layoutPref,
            dashboardLayoutFlow = layoutState,
            persistLayoutUpdates = true
        )

        firstViewModel.uiState.test {
            awaitStable { it.widgetOrder.isNotEmpty() }
            firstViewModel.moveWidget(from = 0, to = 2)
            awaitStable { it.widgetOrder[2] == DashboardWidget.BALANCE.id }
            cancelAndConsumeRemainingEvents()
        }

        val recreatedViewModel = newViewModel(
            layoutPref = layoutPref,
            dashboardLayoutFlow = layoutState,
            persistLayoutUpdates = true
        )
        recreatedViewModel.uiState.test {
            val restored = awaitStable { it.widgetOrder[2] == DashboardWidget.BALANCE.id }
            assertEquals(DashboardWidget.BALANCE.id, restored.widgetOrder[2])
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `ocultar y restaurar widget`() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.uiState.test {
            awaitStable { it.widgetOrder.isNotEmpty() }
            vm.hideWidget("balance")

            val hiddenState = awaitItem()
            assertEquals(setOf("balance"), hiddenState.hiddenWidgets)

            vm.restoreWidget("balance")

            val restoredState = awaitItem()
            assertTrue(restoredState.hiddenWidgets.isEmpty())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `reset restaura el orden por defecto y deshacer lo recupera`() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.uiState.test {
            awaitStable { it.widgetOrder.isNotEmpty() }
            vm.moveWidget(0, 2)
            awaitItem()

            vm.resetLayout()

            val resetState = awaitItem()
            assertEquals(DashboardWidget.defaultOrder, resetState.widgetOrder)
            assertTrue(resetState.hiddenWidgets.isEmpty())
            assertEquals(true, resetState.showResetUndo)

            vm.undoResetLayout()

            val undoState = awaitItem()
            assertEquals("cashflow", undoState.widgetOrder[0])
            assertEquals(false, undoState.showResetUndo)
            cancelAndConsumeRemainingEvents()
        }
    }
}
