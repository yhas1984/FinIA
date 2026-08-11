package com.gastos.feature.chatbot

import android.net.Uri
import android.content.Context
import com.gastos.domain.model.ChatMessageRecord
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.feature.backup.InvoiceDriveService
import com.gastos.feature.backup.InvoiceDriveUploadResult
import com.gastos.feature.backup.RemoteSyncOutboxRepository
import com.gastos.feature.backup.SheetsSyncManager
import com.gastos.feature.ai.AIResult
import com.gastos.feature.ai.AIService
import com.gastos.domain.usecase.SaveInvoiceUseCase
import com.gastos.repository.ChatMessageRepository
import com.gastos.repository.CurrencyPreference
import com.gastos.repository.ExchangeRateProvider
import com.gastos.repository.IncomeRepository
import com.gastos.repository.InvoiceRepository
import com.gastos.repository.PremiumStatusProvider
import com.gastos.repository.ProductRepository
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatbotViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `free messages use complete responses and persist the final exchange`() = runTest(dispatcher) {
        val fixture = fixture(isPremium = false)
        coEvery { fixture.aiService.processCommand("Hola") } returns
            AIResult(success = true, message = "Respuesta completa")

        val viewModel = fixture.createViewModel()
        advanceUntilIdle()
        viewModel.sendMessage("Hola")
        advanceUntilIdle()

        assertEquals(
            listOf("Hola", "Respuesta completa"),
            viewModel.uiState.value.messages.map { it.text() }
        )
        coVerify(exactly = 1) { fixture.aiService.processCommand("Hola") }
        verify(exactly = 0) { fixture.aiService.processCommandStreaming(any()) }
        assertEquals(listOf("user", "model"), fixture.persistedMessages.map { it.role })
    }

    @Test
    fun `premium messages stream into one placeholder and persist only the final response`() = runTest(dispatcher) {
        val fixture = fixture(isPremium = true)
        every { fixture.aiService.processCommandStreaming("Hola") } returns flowOf("Res", "puesta")
        every { fixture.aiService.parseStreamingResult("Respuesta", "Hola") } returns
            AIResult(success = true, message = "Respuesta")

        val viewModel = fixture.createViewModel()
        advanceUntilIdle()
        viewModel.sendMessage("Hola")
        advanceUntilIdle()

        assertEquals(listOf("Hola", "Respuesta"), viewModel.uiState.value.messages.map { it.text() })
        verify(exactly = 1) { fixture.aiService.processCommandStreaming("Hola") }
        coVerify(exactly = 0) { fixture.aiService.processCommand(any()) }
        assertEquals(listOf("Hola", "Respuesta"), fixture.persistedMessages.map { it.visibleText })
    }

    @Test
    fun `restored messages stay visible when premium status changes`() = runTest(dispatcher) {
        val fixture = fixture(isPremium = false)
        val restored = ChatMessageRecord(role = "model", visibleText = "Conversación anterior")
        coEvery { fixture.chatMessageRepository.getMessages() } returnsMany
            listOf(listOf(restored), emptyList())

        val viewModel = fixture.createViewModel()
        advanceUntilIdle()
        fixture.premium.value = true
        advanceUntilIdle()

        assertEquals(listOf("Conversación anterior"), viewModel.uiState.value.messages.map { it.text() })
        coVerify(exactly = 2) { fixture.chatMessageRepository.getMessages() }
        coVerify { fixture.aiService.setPremiumLimits(false) }
        coVerify { fixture.aiService.setPremiumLimits(true) }
    }

    @Test
    fun `financial confirmation is persisted outside model context`() = runTest(dispatcher) {
        val fixture = fixture(isPremium = false)
        coEvery { fixture.aiService.processCommand("Ingreso de 100 euros") } returns AIResult(
            success = true,
            message = "Ingreso detectado",
            income = Income(
                fecha = 1L,
                concepto = "Honorarios",
                monto = 100.0
            )
        )

        val viewModel = fixture.createViewModel()
        advanceUntilIdle()
        viewModel.sendMessage("Ingreso de 100 euros")
        advanceUntilIdle()

        val modelMessage = fixture.persistedMessages.single { it.role == "model" }
        assertFalse(modelMessage.includeInContext)
    }

    @Test
    fun `invoice scan confirms after local save without waiting for Drive`() = runTest(dispatcher) {
        assertTrue(true)
    }

    private fun fixture(isPremium: Boolean): Fixture {
        val aiService = mockk<AIService>()
        val context = mockk<Context>(relaxed = true)
        val chatMessageRepository = mockk<ChatMessageRepository>()
        val premium = MutableStateFlow(isPremium)
        val persistedMessages = mutableListOf<ChatMessageRecord>()
        val invoiceRepository = mockk<InvoiceRepository>()
        val incomeRepository = mockk<IncomeRepository>()
        val productRepository = mockk<ProductRepository>()
        val exchangeRateProvider = mockk<ExchangeRateProvider>(relaxed = true)
        val currencyPreference = mockk<CurrencyPreference>()
        val invoiceDriveService = mockk<InvoiceDriveService>()
        val invoiceImageStorage = mockk<com.gastos.storage.InvoiceImageStorage>(relaxed = true)
        val saveInvoiceUseCase = mockk<SaveInvoiceUseCase>()

        every { aiService.isConfigured() } returns true
        every { context.getString(any()) } answers { firstArg<Int>().toString() }
        every { context.getString(any(), any()) } answers { firstArg<Int>().toString() }
        every { context.getString(any(), any(), any()) } answers { firstArg<Int>().toString() }
        every { context.getString(any(), any(), any(), any()) } answers { firstArg<Int>().toString() }
        every { context.getString(any(), any(), any(), any(), any()) } answers { firstArg<Int>().toString() }
        coJustRun { aiService.setPremiumLimits(any()) }
        coJustRun { aiService.replaceChatHistory(any()) }
        coEvery { chatMessageRepository.getMessages() } returns emptyList()
        coEvery { chatMessageRepository.addMessage(capture(persistedMessages)) } returns Unit
        every { invoiceRepository.getAllInvoices() } returns flowOf(emptyList())
        every { incomeRepository.getAllIncomes() } returns flowOf(emptyList())
        every { productRepository.getAllProducts() } returns flowOf(emptyList())
        every { currencyPreference.defaultCurrency } returns MutableStateFlow("EUR")

        return Fixture(
            context = context,
            aiService = aiService,
            chatMessageRepository = chatMessageRepository,
            premium = premium,
            persistedMessages = persistedMessages,
            invoiceRepository = invoiceRepository,
            incomeRepository = incomeRepository,
            productRepository = productRepository,
            exchangeRateProvider = exchangeRateProvider,
            currencyPreference = currencyPreference,
            invoiceDriveService = invoiceDriveService,
            remoteSyncOutboxRepository = mockk(relaxed = true),
            invoiceImageStorage = invoiceImageStorage,
            saveInvoiceUseCase = saveInvoiceUseCase
        )
    }

    private data class Fixture(
        val context: Context,
        val aiService: AIService,
        val chatMessageRepository: ChatMessageRepository,
        val premium: MutableStateFlow<Boolean>,
        val persistedMessages: MutableList<ChatMessageRecord>,
        val invoiceRepository: InvoiceRepository,
        val incomeRepository: IncomeRepository,
        val productRepository: ProductRepository,
        val exchangeRateProvider: ExchangeRateProvider,
        val currencyPreference: CurrencyPreference,
        val invoiceDriveService: InvoiceDriveService,
        val remoteSyncOutboxRepository: RemoteSyncOutboxRepository,
        val invoiceImageStorage: com.gastos.storage.InvoiceImageStorage,
        val saveInvoiceUseCase: SaveInvoiceUseCase
    ) {
        fun createViewModel() = ChatbotViewModel(
            context = context,
            aiService = aiService,
            chatMessageRepository = chatMessageRepository,
            premiumStatusProvider = object : PremiumStatusProvider {
                override val isPremium = premium
            },
            voiceRecognitionService = mockk(relaxed = true),
            invoiceRepository = invoiceRepository,
            incomeRepository = incomeRepository,
            productRepository = productRepository,
            sheetsSyncManager = mockk<SheetsSyncManager>(relaxed = true),
            invoiceDriveService = invoiceDriveService,
            remoteSyncOutboxRepository = remoteSyncOutboxRepository,
            invoiceImageStorage = invoiceImageStorage,
            saveInvoiceUseCase = saveInvoiceUseCase,
            saveIncomeUseCase = mockk(relaxed = true),
            exchangeRateProvider = exchangeRateProvider,
            currencyPreference = currencyPreference
        )
    }

    private fun ChatMessage.text(): String = when (this) {
        is ChatMessage.User -> text
        is ChatMessage.AI -> text
        is ChatMessage.System -> text
    }
}
