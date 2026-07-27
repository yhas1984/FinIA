package com.gastos.feature.incomes

import com.gastos.domain.model.Income
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.every
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditIncomeViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `saveIncome preserva imagenUri y createdAt del registro original`() =
        runTest(dispatcher) {
            val repo = mockk<com.gastos.repository.IncomeRepository>()
            val sync = mockk<com.gastos.feature.backup.SheetsSyncManager>(relaxed = true)
            val original = Income(
                id = 9L,
                fecha = 1L,
                concepto = "Nómina",
                monto = 1000.0,
                moneda = "EUR",
                imagenUri = "content://scan/9",
                createdAt = 555L
            )
            coEvery { repo.getIncomeById(9L) } returns original
            coEvery { repo.updateIncome(any()) } returns Unit
            every { repo.getAllIncomes() } returns flowOf(listOf(original))

            val vm = EditIncomeViewModel(repo, sync)
            vm.loadIncome(9L)
            advanceUntilIdle()
            vm.updateConcepto("Nómina actualizada")
            vm.saveIncome()
            advanceUntilIdle()

            coVerify {
                repo.updateIncome(match {
                    it.id == 9L &&
                        it.concepto == "Nómina actualizada" &&
                        it.imagenUri == "content://scan/9" &&
                        it.categoria == null &&
                        it.createdAt == 555L
                })
            }
        }

    @Test
    fun `saveIncome rechaza importes no finitos y porcentajes fuera de rango`() =
        runTest(dispatcher) {
            val repo = mockk<com.gastos.repository.IncomeRepository>(relaxed = true)
            val sync = mockk<com.gastos.feature.backup.SheetsSyncManager>(relaxed = true)
            every { repo.getAllIncomes() } returns flowOf(emptyList())
            val vm = EditIncomeViewModel(repo, sync)

            vm.updateConcepto("Ingreso")
            vm.updateMonto("NaN")
            vm.saveIncome()
            advanceUntilIdle()
            assertEquals("El monto debe ser un número positivo", vm.uiState.value.saveResult)

            vm.updateMonto("100")
            vm.updateIvaPercent("101")
            vm.saveIncome()
            advanceUntilIdle()
            assertEquals("Los porcentajes deben estar entre 0 y 100", vm.uiState.value.saveResult)
            coVerify(exactly = 0) { repo.insertIncome(any()) }
        }
}
