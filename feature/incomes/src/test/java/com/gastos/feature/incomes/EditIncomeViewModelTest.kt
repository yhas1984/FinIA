package com.gastos.feature.incomes

import android.content.Context
import com.gastos.feature.incomes.R
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
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditIncomeViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private fun mockContext(): Context = mockk {
        every { getString(R.string.validation_amount_positive) } returns "El monto debe ser un número positivo"
        every { getString(R.string.validation_gross_net_positive) } returns "Devengado y neto deben ser importes positivos"
        every { getString(R.string.validation_percentages_range) } returns "Los porcentajes deben estar entre 0 y 100"
        every { getString(R.string.validation_currency_not_supported) } returns "La moneda seleccionada no está soportada"
        every { getString(R.string.validation_concept_required) } returns "El concepto es obligatorio"
        every { getString(R.string.saved_ok) } returns "Ingreso guardado correctamente"
        every { getString(R.string.load_income_error) } returns "Error al cargar ingreso"
        every { getString(R.string.save_income_error_prefix, any()) } answers { "Error al guardar: ${secondArg<Any?>()?.toString().orEmpty()}" }
    }

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
            val context = mockContext()
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

            val vm = EditIncomeViewModel(context, repo, sync)
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
            val context = mockContext()
            every { repo.getAllIncomes() } returns flowOf(emptyList())
            coEvery { repo.insertIncome(any()) } returns 10L

            val vm = EditIncomeViewModel(context, repo, sync)
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
            val context = mockContext()
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

            val vm = EditIncomeViewModel(context, repo, sync)
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
            val context = mockContext()
            val income = Income(
                id = 1L,
                fecha = 1L,
                concepto = "Ingreso",
                monto = 1.0,
                categoria = "Ventas",
                subcategoria = "Servicios"
            )
            every { repo.getAllIncomes() } returns flowOf(listOf(income))

            val vm = EditIncomeViewModel(context, repo, sync)
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
            val context = mockContext()
            val vm = EditIncomeViewModel(context, repo, sync)

            vm.updateConcepto("Ingreso")
            vm.updateMonto("NaN")
            vm.saveIncome()
            advanceUntilIdle()
            assertEquals("El monto debe ser un número positivo", vm.uiState.value.saveResult)

            vm.updateMonto("100")
            vm.updateIvaPercent("101")
            vm.saveIncome()
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.insertIncome(any()) }
        }
    @Test fun `draft survives recreation without replacing identity or original tax data`() = runTest(dispatcher) {
        val state = androidx.lifecycle.SavedStateHandle()
        val repo = mockk<com.gastos.repository.IncomeRepository>()
        val sync = mockk<com.gastos.feature.backup.SheetsSyncManager>(relaxed = true)
        val original = Income(id = -8, documentUuid = "stable-legacy", fecha = 1, concepto = "Original", monto = 100.0, ivaPercent = null, driveFileId = "remote", financialRevision = 4)
        every { repo.getAllIncomes() } returns flowOf(listOf(original))
        coEvery { repo.getIncomeById(-8) } returns original
        coEvery { repo.updateIncome(any()) } returns Unit
        val first = EditIncomeViewModel(mockContext(), repo, sync, savedStateHandle = state)
        first.loadIncome(-8, java.util.Locale.US)
        advanceUntilIdle()
        org.junit.Assert.assertFalse(first.hasChanges)
        first.updateConcepto("Changed")
        advanceUntilIdle()
        val restored = androidx.lifecycle.SavedStateHandle(state.keys().associateWith { state.get<Any?>(it) })
        val second = EditIncomeViewModel(mockContext(), repo, sync, savedStateHandle = restored)
        second.loadIncome(-8, java.util.Locale.US)
        advanceUntilIdle()
        org.junit.Assert.assertEquals("Changed", second.form.value.concepto)
        org.junit.Assert.assertTrue(second.hasChanges)
        org.junit.Assert.assertEquals("", second.form.value.totalDevengado)
        org.junit.Assert.assertEquals("", second.form.value.totalNeto)
        second.saveIncome(java.util.Locale.US)
        advanceUntilIdle()
        coVerify(exactly = 1) { repo.updateIncome(match { it.documentUuid == "stable-legacy" && it.id == -8L && it.ivaPercent == null && it.driveFileId == "remote" && it.financialRevision == 4L && it.concepto == "Changed" }) }
    }


    @Test fun `new form waits for saved preferences once and restored draft takes precedence`() = runTest(dispatcher) {
        val context = mockContext()
        val repo = mockk<com.gastos.repository.IncomeRepository>(relaxed=true)
        every { repo.getAllIncomes() } returns flowOf(emptyList())

        val sync = mockk<com.gastos.feature.backup.SheetsSyncManager>(relaxed=true)
        val preferences = mockk<com.gastos.repository.ManualEntryDefaultsProvider>()
        coEvery { preferences.manualEntryDefaults() } returns com.gastos.repository.ManualEntryDefaults("USD","US")
        val state = androidx.lifecycle.SavedStateHandle()
        val model = EditIncomeViewModel(context, repo, sync, savedStateHandle=state, entryDefaults=preferences)
        assertTrue(!model.uiState.value.defaultsReady)
        model.saveIncome()
        coVerify(exactly=0) { repo.insertIncome(any()) }
        model.prepareDefaults(); advanceUntilIdle()
        model.prepareDefaults(); advanceUntilIdle()
        assertEquals("USD",model.form.value.moneda)
        assertEquals("US",model.form.value.paisCodigo)
        assertTrue(!model.hasChanges)
        model.updateMoneda("GBP")
        val recreated = EditIncomeViewModel(context, repo, sync, savedStateHandle=state, entryDefaults=preferences)
        recreated.prepareDefaults(); advanceUntilIdle()
        assertEquals("GBP",recreated.form.value.moneda)
        coVerify(exactly=1) { preferences.manualEntryDefaults() }
    }
    @Test fun `validation exposes field errors only after save and preserves all values`() = runTest(dispatcher) {
        val context = mockContext()
        val repo = mockk<com.gastos.repository.IncomeRepository>(relaxed=true)
        every { repo.getAllIncomes() } returns flowOf(emptyList())

        val sync = mockk<com.gastos.feature.backup.SheetsSyncManager>(relaxed=true)
        val model = EditIncomeViewModel(context, repo, sync)
        assertTrue(model.uiState.value.fieldErrors.isEmpty())
        model.updateMonto("12,50")
        model.updateIvaPercent("150")
        model.saveIncome(java.util.Locale("es","ES")); advanceUntilIdle()
        assertEquals(setOf(com.gastos.common.ManualField.CONCEPT,com.gastos.common.ManualField.VAT),model.uiState.value.fieldErrors.keys)
        assertEquals("12,50",model.form.value.monto)
        assertEquals("150",model.form.value.ivaPercent)
        coVerify(exactly=0) { repo.insertIncome(any()) }
    }

    @Test fun `double save waits for local persistence and storage failure retains the draft`() = runTest(dispatcher) {
        val context = mockContext()
        val repo = mockk<com.gastos.repository.IncomeRepository>(relaxed=true)
        every { repo.getAllIncomes() } returns flowOf(emptyList())

        val sync = mockk<com.gastos.feature.backup.SheetsSyncManager>(relaxed=true)
        val commit = kotlinx.coroutines.CompletableDeferred<Long>()
        coEvery { repo.insertIncome(any()) } coAnswers { commit.await() }
        val model = EditIncomeViewModel(context,repo,sync)
        model.updateConcepto("Never committed")
        model.updateMonto("12.50")
        model.saveIncome(java.util.Locale.US)
        model.saveIncome(java.util.Locale.US)
        assertTrue(model.uiState.value.isSaving)
        coVerify(exactly=1) { repo.insertIncome(any()) }
        commit.completeExceptionally(java.io.IOException("synthetic disk failure"))
        advanceUntilIdle()
        assertTrue(model.uiState.value.saveState is com.gastos.common.SaveState.Error)
        assertTrue(model.uiState.value.fieldErrors.isEmpty())
        assertEquals("Never committed",model.form.value.concepto)
        assertEquals("12.50",model.form.value.monto)
        coVerify(exactly=1) { repo.insertIncome(any()) }
    }
}
