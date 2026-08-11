package com.gastos.feature.invoices

import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.TransactionCategories
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
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
            val productRepo = mockk<com.gastos.repository.ProductRepository>()
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
                driveFileId = "drive-5",
                driveWebViewLink = "https://drive.google.com/file/d/drive-5/view",
                driveUploadPending = true,
                ocrRawText = """{"numero_factura":"F-9"}""",
                createdAt = 100L
            )
            coEvery { repo.getInvoiceById(5L) } returns original
            coEvery { repo.updateInvoice(any()) } returns Unit
            every { repo.getAllInvoices() } returns flowOf(listOf(original))
            every { productRepo.getProductsByInvoiceId(5L) } returns kotlinx.coroutines.flow.flowOf(emptyList())

            val vm = EditInvoiceViewModel(repo, productRepo, sync)
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
                        it.driveFileId == "drive-5" &&
                        it.driveWebViewLink == "https://drive.google.com/file/d/drive-5/view" &&
                        it.driveUploadPending &&
                        it.categoria == null &&
                        it.baseImponible == null &&
                        it.cuotaIva == null &&
                        it.ocrRawText == original.ocrRawText &&
                        it.createdAt == 100L
                })
            }
        }

    @Test
    fun `saveInvoice persiste subcategoria normalizada`() = runTest(dispatcher) {
        val repo = mockk<com.gastos.repository.InvoiceRepository>()
        val productRepo = mockk<com.gastos.repository.ProductRepository>()
        val sync = mockk<com.gastos.feature.backup.SheetsSyncManager>(relaxed = true)
        every { repo.getAllInvoices() } returns flowOf(emptyList())
        coEvery { repo.insertInvoice(any()) } returns 10L

        val vm = EditInvoiceViewModel(repo, productRepo, sync)
        vm.updateProveedor("Acme")
        vm.updateTotal("121")
        vm.selectCategory("Alimentación", isCustomCategory = false)
        vm.selectSubcategory("  supermercado  ", isCustom = true)
        vm.saveInvoice()
        advanceUntilIdle()

        coVerify {
            repo.insertInvoice(match {
                it.subcategoria == "supermercado" &&
                    it.categoria == "Alimentación" &&
                    it.tipo == InvoiceType.GASTO
            })
        }
    }

    @Test
    fun `saveInvoice rechaza un desglose fiscal inconsistente`() = runTest(dispatcher) {
        val repo = mockk<com.gastos.repository.InvoiceRepository>()
        val productRepo = mockk<com.gastos.repository.ProductRepository>()
        val sync = mockk<com.gastos.feature.backup.SheetsSyncManager>(relaxed = true)
        every { repo.getAllInvoices() } returns flowOf(emptyList())

        val vm = EditInvoiceViewModel(repo, productRepo, sync)
        vm.updateProveedor("Acme")
        vm.updateTotal("121")
        vm.updateBaseImponible("90")
        vm.updateCuotaIva("21")
        vm.saveInvoice()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.saveResult?.contains("no coinciden") == true)
        coVerify(exactly = 0) { repo.insertInvoice(any()) }
    }

    @Test
    fun `loadInvoice restaura subcategoria`() = runTest(dispatcher) {
        val repo = mockk<com.gastos.repository.InvoiceRepository>()
        val productRepo = mockk<com.gastos.repository.ProductRepository>()
        val sync = mockk<com.gastos.feature.backup.SheetsSyncManager>(relaxed = true)
        val original = Invoice(
            id = 7L,
            fecha = 1L,
            proveedor = "Acme",
            tipo = InvoiceType.GASTO,
            categoria = "Alimentación",
            subcategoria = "Supermercado",
            total = 121.0,
            ivaPercent = 21.0,
            irpfPercent = 0.0
        )
        coEvery { repo.getInvoiceById(7L) } returns original
        every { repo.getAllInvoices() } returns flowOf(listOf(original))

        val vm = EditInvoiceViewModel(repo, productRepo, sync)
        vm.loadInvoice(7L)
        advanceUntilIdle()

        assert(vm.form.value.subcategoria == "Supermercado")
        assert(vm.form.value.isCustomSubcategory.not())
    }

    @Test
    fun `selectCategory actualiza availableSubcategories`() = runTest(dispatcher) {
        val repo = mockk<com.gastos.repository.InvoiceRepository>()
        val productRepo = mockk<com.gastos.repository.ProductRepository>()
        val sync = mockk<com.gastos.feature.backup.SheetsSyncManager>(relaxed = true)
        every { repo.getAllInvoices() } returns flowOf(listOf(Invoice(id = 1L, fecha = 1L, proveedor = "A", tipo = InvoiceType.GASTO, total = 1.0, ivaPercent = 0.0, irpfPercent = 0.0, subcategoria = "Supermercado")))

        val vm = EditInvoiceViewModel(repo, productRepo, sync)
        vm.selectCategory("Alimentación", isCustomCategory = false)
        advanceUntilIdle()

        assert(vm.uiState.value.availableSubcategories.containsAll(
            TransactionCategories.suggestedSubcategories("Alimentación", isIncome = false)
        ))
        assert(vm.uiState.value.availableSubcategories.contains("Supermercado"))
    }
}
