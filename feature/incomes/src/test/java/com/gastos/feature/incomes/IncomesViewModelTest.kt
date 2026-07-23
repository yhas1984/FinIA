package com.gastos.feature.incomes

import app.cash.turbine.test
import com.gastos.domain.model.Income
import com.gastos.feature.backup.SheetsSyncManager
import com.gastos.repository.CurrencyPreference
import com.gastos.repository.ExchangeRateProvider
import com.gastos.repository.IncomeRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IncomesViewModelTest {

    private fun income(id: Long, monto: Double, moneda: String) =
        Income(
            id = id,
            fecha = 1L,
            concepto = "C$id",
            monto = monto,
            moneda = moneda
        )

    private fun newViewModel(
        incomes: List<Income>,
        rates: Map<String, Double> = mapOf("EUR" to 1.0),
        defaultCurrency: String = "EUR"
    ): IncomesViewModel {
        val repo = mockk<IncomeRepository>()
        every { repo.getAllIncomes() } returns flowOf(incomes)
        val exchange = mockk<ExchangeRateProvider>()
        every { exchange.rates } returns MutableStateFlow(rates)
        every { exchange.convert(any(), any(), any()) } answers {
            val amount = firstArg<Double>()
            val from = secondArg<String>().uppercase()
            val to = thirdArg<String>().uppercase()
            val rFrom = rates[from] ?: return@answers null
            val rTo = rates[to] ?: return@answers null
            amount * rTo / rFrom
        }
        val currency = mockk<CurrencyPreference>()
        every { currency.defaultCurrency } returns MutableStateFlow(defaultCurrency)
        val sync = mockk<SheetsSyncManager>(relaxed = true)
        return IncomesViewModel(repo, sync, exchange, currency)
    }

    @Test
    fun `recomputeTotal excluye ingresos sin tasa`() = runTest(UnconfinedTestDispatcher()) {
        val incomes = listOf(
            income(1, 100.0, "EUR"),
            income(2, 50.0, "USD")
        )
        val vm = newViewModel(incomes, rates = mapOf("EUR" to 1.0))

        vm.uiState.test {
            var state = awaitItem()
            while (state.isLoading || state.incomes.isEmpty()) {
                state = awaitItem()
            }
            assertEquals(100.0, state.totalIngresosConvertido!!, 0.001)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `recomputeTotal convierte cuando hay tasas`() = runTest(UnconfinedTestDispatcher()) {
        val incomes = listOf(
            income(1, 100.0, "EUR"),
            income(2, 100.0, "USD")
        )
        val vm = newViewModel(incomes, rates = mapOf("EUR" to 1.0, "USD" to 0.5))

        vm.uiState.test {
            var state = awaitItem()
            while (state.isLoading || state.incomes.isEmpty()) {
                state = awaitItem()
            }
            // Tasas: 1 USD = 1 EUR y 1 USD = 0.5 USD-test.
            // 100 USD-test = 200 EUR; total = 300 EUR.
            assertEquals(300.0, state.totalIngresosConvertido!!, 0.001)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `recomputeTotal devuelve null si TODOS los ingresos carecen de tasa`() =
        runTest(UnconfinedTestDispatcher()) {
            val incomes = listOf(income(1, 50.0, "USD"))
            val vm = newViewModel(incomes, rates = mapOf("EUR" to 1.0))

            vm.uiState.test {
                var state = awaitItem()
                while (state.isLoading || state.incomes.isEmpty()) {
                    state = awaitItem()
                }
                assertNull(state.totalIngresosConvertido)
                cancelAndConsumeRemainingEvents()
            }
        }
}
