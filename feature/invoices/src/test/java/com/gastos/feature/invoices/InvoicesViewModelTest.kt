package com.gastos.feature.invoices

import app.cash.turbine.test
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.feature.backup.SheetsSyncManager
import com.gastos.feature.backup.InvoiceDriveService
import com.gastos.repository.CurrencyPreference
import com.gastos.repository.ExchangeRateProvider
import com.gastos.repository.InvoiceRepository
import com.gastos.repository.PremiumStatusProvider
import com.gastos.storage.InvoiceImageStorage
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
class InvoicesViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private fun invoice(id: Long, total: Double, moneda: String, tipo: InvoiceType = InvoiceType.GASTO) =
        Invoice(
            id = id,
            fecha = 1L,
            proveedor = "P$id",
            tipo = tipo,
            moneda = moneda,
            total = total
        )

    private fun newViewModel(
        invoices: List<Invoice>,
        rates: Map<String, Double> = mapOf("EUR" to 1.0),
        defaultCurrency: String = "EUR"
    ): InvoicesViewModel {
        val repo = mockk<InvoiceRepository>()
        every { repo.getAllInvoices() } returns flowOf(invoices)
        every { repo.getInvoicesByType(any()) } returns flowOf(invoices)
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
        val drive = mockk<InvoiceDriveService>(relaxed = true)
        val imageStorage = mockk<InvoiceImageStorage>(relaxed = true)
        val premium = mockk<PremiumStatusProvider>()
        every { premium.isPremium } returns MutableStateFlow(false)
        return InvoicesViewModel(repo, sync, exchange, currency, drive, imageStorage, premium)
    }

    @Test
    fun `recomputeTotal excluye gastos sin tasa`() = runTest(dispatcher) {
        val invoices = listOf(
            invoice(1, 100.0, "EUR"),
            invoice(2, 50.0, "USD")
        )
        val vm = newViewModel(invoices, rates = mapOf("EUR" to 1.0))

        vm.uiState.test {
            // Se omite el state inicial (isLoading=true) y se esperan las
            // actualizaciones hasta llegar al estado estable con totales.
            var state = awaitItem()
            while (state.isLoading || state.invoices.isEmpty()) {
                state = awaitItem()
            }
            // USD no tiene tasa → no se suma.
            assertEquals(100.0, state.totalGastosConvertido!!, 0.001)
            assertEquals("EUR", state.defaultCurrency)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `recomputeTotal convierte cuando hay tasas`() = runTest(dispatcher) {
        val invoices = listOf(
            invoice(1, 100.0, "EUR"),
            invoice(2, 100.0, "USD")
        )
        val vm = newViewModel(invoices, rates = mapOf("EUR" to 1.0, "USD" to 0.9))

        vm.uiState.test {
            var state = awaitItem()
            while (state.isLoading || state.invoices.isEmpty()) {
                state = awaitItem()
            }
            // Tasas: 1 USD = 1 EUR y 1 USD = 0.9 USD-test.
            // 100 USD-test = 111.11 EUR; total = 211.11 EUR.
            assertEquals(211.111, state.totalGastosConvertido!!, 0.001)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `recomputeTotal devuelve null si TODOS los gastos carecen de tasa`() = runTest(dispatcher) {
        val invoices = listOf(invoice(1, 50.0, "USD"))
        val vm = newViewModel(invoices, rates = mapOf("EUR" to 1.0))

        vm.uiState.test {
            var state = awaitItem()
            while (state.isLoading || state.invoices.isEmpty()) {
                state = awaitItem()
            }
            assertNull(state.totalGastosConvertido)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `recomputeTotal suma cero cuando no hay gastos`() = runTest(dispatcher) {
        val invoices = listOf(
            invoice(1, 50.0, "EUR", tipo = InvoiceType.INGRESO)
        )
        val vm = newViewModel(invoices)

        vm.uiState.test {
            var state = awaitItem()
            while (state.isLoading || state.invoices.isEmpty()) {
                state = awaitItem()
            }
            assertEquals(0.0, state.totalGastosConvertido!!, 0.001)
            cancelAndConsumeRemainingEvents()
        }
    }
}
