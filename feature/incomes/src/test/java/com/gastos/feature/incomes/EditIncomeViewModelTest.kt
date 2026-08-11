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
                        it.subcategoria == null &&
                        it.createdAt == 555L
                })
            }
        }

    @Test
    fun `saveIncome persiste subcategoria normalizada`() =
        runTest(dispatcher) {
            val repo = mockk<com.gastos.repository.IncomeRepository>()
            val sync = mockk<com.gastos.feature.backup.SheetsSyncManager>(relaxed = true)
            every { repo.getAllIncomes() } returns flowOf(emptyList())
            coEvery { repo.insertIncome(any()) } returns 10L

            val vm = EditIncomeViewModel(repo, sync)
            vm.updateConcepto("Ingreso")
            vm.updateMonto("100")
            vm.selectCategory("Ventas", isCustomCategory = false)
            vm.selectSubcategory("  Servicios  ", isCustom = true)
            vm.saveIncome()
            advanceUntilIdle()

            coVerify {
                repo.insertIncome(match {
                    it.categoria == "Ventas" &&
                        it.subcategoria == "Servicios"
                })
            }
        }

    @Test
    fun `loadIncome restaura subcategoria`() =
        runTest(dispatcher) {
            val repo = mockk<com.gastos.repository.IncomeRepository>()
            val sync = mockk<com.gastos.feature.backup.SheetsSyncManager>(relaxed = true)
            val income = Income(
                id = 7L,
                fecha = 1L,
                concepto = "Venta",
                monto = 100.0,
                categoria = "Ventas",
                subcategoria = "Productos"
            )
            every { repo.getAllIncomes() } returns flowOf(listOf(income))
            coEvery { repo.getIncomeById(7L) } returns income

            val vm = EditIncomeViewModel(repo, sync)
            vm.loadIncome(7L)
            advanceUntilIdle()

            assertEquals("Productos", vm.form.value.subcategoria)
            assertEquals(false, vm.form.value.isCustomSubcategory)
        }

    @Test
    fun `selectCategory actualiza subcategorias disponibles`() =
        runTest(dispatcher) {
            val repo = mockk<com.gastos.repository.IncomeRepository>()
            val sync = mockk<com.gastos.feature.backup.SheetsSyncManager>(relaxed = true)
            val income = Income(
                id = 1L,
                fecha = 1L,
                concepto = "Ingreso",
                monto = 1.0,
                categoria = "Ventas",
                subcategoria = "Servicios"
            )
            every { repo.getAllIncomes() } returns flowOf(listOf(income))

            val vm = EditIncomeViewModel(repo, sync)
            vm.selectCategory("Nómina", isCustomCategory = false)
            advanceUntilIdle()

            assertEquals(listOf("Salario base", "Extras", "Pagas extra", "Servicios"), vm.uiState.value.availableSubcategories)
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
