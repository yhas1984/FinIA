package com.gastos.feature.invoices

import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditInvoiceViewModelTest {

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
    fun `saveInvoice preserva imagenUri y ocrRawText del registro original`() =
        runTest(dispatcher) {
            val repo = mockk<com.gastos.repository.InvoiceRepository>()
            val sync = mockk<com.gastos.feature.backup.SheetsSyncManager>(relaxed = true)
            val original = Invoice(
                id = 5L,
                fecha = 1L,
                proveedor = "Acme",
                tipo = InvoiceType.GASTO,
                total = 121.0,
                ivaPercent = 21.0,
                irpfPercent = 0.0,
                imagenUri = "content://scan/5",
                ocrRawText = """{"numero_factura":"F-9"}""",
                createdAt = 100L
            )
            coEvery { repo.getInvoiceById(5L) } returns original
            coEvery { repo.updateInvoice(any()) } returns Unit

            val vm = EditInvoiceViewModel(repo, sync)
            vm.loadInvoice(5L)
            advanceUntilIdle()
            vm.updateProveedor("Acme Editado")
            vm.saveInvoice()
            advanceUntilIdle()

            coVerify {
                repo.updateInvoice(match {
                    it.id == 5L &&
                        it.proveedor == "Acme Editado" &&
                        it.imagenUri == "content://scan/5" &&
                        it.ocrRawText == original.ocrRawText &&
                        it.createdAt == 100L
                })
            }
        }
}
