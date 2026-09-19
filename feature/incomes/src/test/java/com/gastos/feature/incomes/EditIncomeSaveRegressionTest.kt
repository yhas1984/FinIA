package com.gastos.feature.incomes

import android.content.Context
import com.gastos.common.SaveState
import com.gastos.domain.model.Income
import com.gastos.repository.IncomeRepository
import com.gastos.feature.backup.SheetsSyncManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.util.Locale

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EditIncomeSaveRegressionTest {
    private val dispatcher = StandardTestDispatcher()
    private val context = mockk<Context> {
        every { getString(any()) } returns "Validation failed"
        every { getString(any(), any()) } returns "Save failed"
    }
    private val repo = mockk<IncomeRepository>(relaxed = true) {
        every { getAllIncomes() } returns flowOf(emptyList())
    }
    private val sync = mockk<SheetsSyncManager>(relaxed = true)
    private val es = Locale.forLanguageTag("es-ES")
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }
    @Test fun `invalid percentage and database failure preserve entered values`() = runTest(dispatcher) {
        val vm=EditIncomeViewModel(context,repo,sync)
        vm.updateConcepto("Synthetic")
        vm.updateMonto("12,50")
        vm.updateIvaPercent("150")
        vm.saveIncome(es)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.saveState is SaveState.Error)
        assertEquals("12,50",vm.form.value.monto)
        coVerify(exactly=0) { repo.insertIncome(any()) }
        vm.updateIvaPercent("4")
        coEvery { repo.insertIncome(any()) } throws java.io.IOException("disk failure")
        vm.saveIncome(es)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.saveState is SaveState.Error)
        assertEquals("Synthetic",vm.form.value.concepto)
        assertEquals("4",vm.form.value.ivaPercent)
    }
    @Test fun `comma amount is persisted once even after double tap and remote failure`() = runTest(dispatcher) {
        coEvery { repo.insertIncome(any()) } returns 4L
        coEvery { sync.upsertIncome(any()) } throws java.io.IOException("offline")
        val vm=EditIncomeViewModel(context,repo,sync)
        vm.updateConcepto("Synthetic"); vm.updateMonto("1.234,50")
        vm.saveIncome(es); vm.saveIncome(es)
        advanceUntilIdle()
        assertEquals(SaveState.Success,vm.uiState.value.saveState)
        coVerify(exactly=1) { repo.insertIncome(match { it.monto==1234.5 && it.ivaPercent==0.0 }) }
        vm.saveIncome(es)
        coVerify(exactly=1) { repo.insertIncome(any()) }
    }
    @Test fun `editing preserves image identity and actual tax`() = runTest(dispatcher) {
        val original=Income(id=1,fecha=1,concepto="Synthetic",monto=110.5,ivaPercent=10.0,
            driveFileId="file",driveAccountId="account",imagenUri="local")
        coEvery { repo.getIncomeById(1) } returns original
        val vm=EditIncomeViewModel(context,repo,sync)
        vm.loadIncome(1,es); advanceUntilIdle()
        assertEquals("110,5",vm.form.value.monto)
        assertEquals("10",vm.form.value.ivaPercent)
        vm.updateConcepto("Edited"); vm.saveIncome(es); advanceUntilIdle()
        coVerify { repo.updateIncome(match { it.documentUuid==original.documentUuid && it.driveFileId=="file" && it.ivaPercent==10.0 }) }
    }
}
