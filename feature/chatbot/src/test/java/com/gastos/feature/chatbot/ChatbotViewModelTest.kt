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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatbotViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        io.mockk.mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        io.mockk.unmockkStatic(android.util.Log::class)
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

        val receipt = ChatMessageRecord(id = 4, role = "document", visibleText = "Saved 100 EUR", includeInContext = false)
        coEvery { fixture.commandOperations.commit(any(), any(), any(), any()) } coAnswers {
            fixture.persistedMessages.add(receipt)
            fixture.documentMessages.value = listOf(receipt)
            com.gastos.storage.CommandCommit(income = thirdArg<Income?>(), receipt = receipt)
        }
        val viewModel = fixture.createViewModel()
        advanceUntilIdle()
        viewModel.sendMessage("Ingreso de 100 euros")
        advanceUntilIdle()

        val modelMessage = fixture.persistedMessages.single { it.role == "document" }
        assertFalse(modelMessage.includeInContext)
    }

    @Test
    fun `interrupted stream preserves text and retry replaces it without duplicating the user`() = runTest(dispatcher) {
        val fixture = fixture(isPremium = true)
        every { fixture.aiService.processCommandStreaming("Hola") } returns flow {
            emit("Respuesta parcial")
            throw java.io.IOException("offline")
        }
        coEvery { fixture.chatMessageRepository.replaceLastIncomplete(any()) } answers {
            fixture.persistedMessages.removeAt(fixture.persistedMessages.lastIndex)
            fixture.persistedMessages.add(firstArg())
        }
        val viewModel = fixture.createViewModel()
        advanceUntilIdle()
        viewModel.sendMessage("Hola")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.canRetryIncomplete)
        assertTrue(viewModel.uiState.value.messages.last().text().contains("Respuesta parcial"))
        assertEquals("model_incomplete", fixture.persistedMessages.last().role)
        assertFalse(fixture.persistedMessages.last().includeInContext)
        every { fixture.aiService.processCommandStreaming("Hola") } returns flowOf("Respuesta completa")
        every { fixture.aiService.parseStreamingResult("Respuesta completa", "Hola") } returns
            AIResult(success = true, message = "Respuesta completa")
        viewModel.retryIncompleteResponse()
        advanceUntilIdle()
        assertEquals(listOf("Hola", "Respuesta completa"), viewModel.uiState.value.messages.map { it.text() })
        assertEquals(listOf("user", "model"), fixture.persistedMessages.map { it.role })
        assertFalse(viewModel.uiState.value.canRetryIncomplete)
    }

    @Test
    fun `captured documents appear live exactly once and survive chat recreation`() = runTest(dispatcher) {
        val fixture = fixture(isPremium = false)
        val model = fixture.createViewModel()
        advanceUntilIdle()
        val receipt = ChatMessageRecord(id = 7, role = "document", visibleText = "Gasto registrado\nSynthetic\n100 EUR",
            includeInContext = false, createdAt = 30)
        fixture.documentMessages.value = listOf(receipt)
        advanceUntilIdle()
        assertEquals(listOf(receipt.visibleText), model.uiState.value.messages.map { it.text() })
        fixture.documentMessages.value = listOf(receipt.copy(visibleText = "Updated receipt"))
        advanceUntilIdle()
        assertEquals(1, model.uiState.value.messages.size)
        coEvery { fixture.chatMessageRepository.getMessages() } returns fixture.documentMessages.value
        val recreated = fixture.createViewModel()
        advanceUntilIdle()
        assertEquals(model.uiState.value.messages, recreated.uiState.value.messages)
        coJustRun { fixture.aiService.resetChat() }
        coEvery { fixture.chatMessageRepository.clearAll() } answers { fixture.documentMessages.value = emptyList() }
        model.clearChat()
        advanceUntilIdle()
        assertTrue(model.uiState.value.messages.isEmpty())
        coVerify(exactly = 0) { fixture.aiService.processCommand(any()) }
        verify(exactly = 0) { fixture.aiService.processCommandStreaming(any()) }
    }

    @Test
    fun `document arriving during streaming preserves the active response`() = runTest(dispatcher) {
        val fixture = fixture(isPremium = true)
        val finish = CompletableDeferred<Unit>()
        every { fixture.aiService.processCommandStreaming("Hola") } returns flow {
            emit("Respuesta")
            finish.await()
            emit(" completa")
        }
        every { fixture.aiService.parseStreamingResult("Respuesta completa", "Hola") } returns
            AIResult(success = true, message = "Respuesta completa")
        val model = fixture.createViewModel()
        advanceUntilIdle()
        model.sendMessage("Hola")
        advanceUntilIdle()
        fixture.documentMessages.value = listOf(ChatMessageRecord(id = 1, role = "document", visibleText = "Gasto registrado", includeInContext = false))
        advanceUntilIdle()
        assertEquals("Respuesta", model.uiState.value.messages.last().text())
        finish.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, model.uiState.value.messages.filterIsInstance<ChatMessage.Document>().size)
        assertEquals("Respuesta completa", model.uiState.value.messages.filterIsInstance<ChatMessage.AI>().single().text)
        assertEquals(3, model.uiState.value.messages.size)
    }

    @Test
    fun `retry after a capture replaces the incomplete response and preserves the receipt`() = runTest(dispatcher) {
        val fixture = fixture(isPremium = true)
        val receipt = ChatMessageRecord(id = 3, role = "document", visibleText = "Ingreso registrado", includeInContext = false, createdAt = 30)
        coEvery { fixture.chatMessageRepository.getMessages() } returns listOf(
            ChatMessageRecord(id = 1, role = "user", visibleText = "Hola", createdAt = 10),
            ChatMessageRecord(id = 2, role = "model_incomplete", visibleText = "Parcial", contextText = "Hola", createdAt = 20), receipt)
        fixture.documentMessages.value = listOf(receipt)
        coJustRun { fixture.chatMessageRepository.replaceLastIncomplete(any()) }
        every { fixture.aiService.processCommandStreaming("Hola") } returns flowOf("Completa")
        every { fixture.aiService.parseStreamingResult("Completa", "Hola") } returns AIResult(success = true, message = "Completa")
        val model = fixture.createViewModel()
        advanceUntilIdle()
        assertTrue(model.uiState.value.canRetryIncomplete)
        model.retryIncompleteResponse()
        advanceUntilIdle()
        assertEquals(1, model.uiState.value.messages.filterIsInstance<ChatMessage.Document>().size)
        assertEquals(listOf("Completa"), model.uiState.value.messages.filterIsInstance<ChatMessage.AI>().map { it.text })
        coVerify(exactly = 1) { fixture.chatMessageRepository.replaceLastIncomplete(any()) }
        coVerify(exactly = 0) { fixture.chatMessageRepository.addMessage(any()) }
    }

    @Test
    fun `invalid streamed date displays the validation error without exposing or saving the command`() = runTest(dispatcher) {
        val fixture = fixture(isPremium = true)
        val raw = "```json\n{\"action\":\"add_expense\",\"fecha\":\"2026-02-31\",\"total\":9}\n```"
        every { fixture.aiService.processCommandStreaming("Gasto el 31/02/2026") } returns flowOf(raw)
        every { fixture.aiService.parseStreamingResult(raw, any()) } returns AIResult(false, "Fecha inválida")
        every { fixture.context.getString(R.string.chatbot_processing_error, any()) } answers { "Error: ${secondArg<Array<Any>>().single()}" }
        val model = fixture.createViewModel()
        advanceUntilIdle()
        model.sendMessage("Gasto el 31/02/2026")
        advanceUntilIdle()
        assertEquals("Error: Fecha inválida", model.uiState.value.messages.last().text())
        assertEquals("Error: Fecha inválida", fixture.persistedMessages.last().visibleText)
        assertTrue(model.uiState.value.canRetryIncomplete)
        assertFalse(fixture.persistedMessages.last().includeInContext)
        coVerify(exactly = 0) { fixture.commandOperations.commit(any(), any(), any(), any()) }
    }

    @Test
    fun `failed local commit does not reveal the buffered structured command`() = runTest(dispatcher) {
        val fixture = fixture(isPremium = true)
        val raw = "{\"action\":\"add_income\",\"monto\":35}"
        every { fixture.aiService.processCommandStreaming("Ingreso de 35 EUR") } returns flowOf(raw)
        every { fixture.aiService.parseStreamingResult(raw, any()) } returns AIResult(
            success = true, message = "", income = Income(fecha = 1L, concepto = "Test", monto = 35.0))
        coEvery { fixture.commandOperations.commit(any(), any(), any(), any()) } throws java.io.IOException("Database unavailable")
        val model = fixture.createViewModel()
        advanceUntilIdle()
        model.sendMessage("Ingreso de 35 EUR")
        advanceUntilIdle()
        assertFalse(model.uiState.value.messages.last().text().contains("action"))
        assertFalse(fixture.persistedMessages.last().visibleText.contains("action"))
        assertEquals("model_incomplete", fixture.persistedMessages.last().role)
        assertTrue(model.uiState.value.canRetryIncomplete)
        coVerify(exactly = 0) { fixture.commandOperations.finish(any()) }
    }

    @Test
    fun `cancelled structured stream never persists raw JSON`() = runTest(dispatcher) {
        val fixture = fixture(isPremium = true)
        every { fixture.aiService.processCommandStreaming("Ingreso") } returns flow {
            emit("{\"action\":\"add_income\"")
            throw kotlinx.coroutines.CancellationException("Cancelled")
        }
        val model = fixture.createViewModel()
        advanceUntilIdle()
        model.sendMessage("Ingreso")
        advanceUntilIdle()
        assertEquals(R.string.chatbot_response_incomplete.toString(), model.uiState.value.messages.last().text())
        assertEquals(R.string.chatbot_response_incomplete.toString(), fixture.persistedMessages.last().visibleText)
        assertEquals("Ingreso", fixture.persistedMessages.last().contextText)
        assertFalse(model.uiState.value.isProcessing)
        coVerify(exactly = 0) { fixture.commandOperations.commit(any(), any(), any(), any()) }
    }

    @Test
    fun `restored legacy commands are not rendered replayed or sent back to Gemini`() = runTest(dispatcher) {
        val fixture = fixture(isPremium = true)
        val user = ChatMessageRecord(role = "user", visibleText = "{user text}", createdAt = 1)
        val command = ChatMessageRecord(role = "model", visibleText = "```json\n{\"action\":\"add_expense\"}\n```", createdAt = 2)
        val receipt = ChatMessageRecord(id = 3, role = "document", visibleText = "Saved receipt", createdAt = 3)
        val partial = ChatMessageRecord(role = "model_incomplete", visibleText = "{\"action\":\"add_income\"\n\nIncomplete response",
            contextText = "Ingreso", includeInContext = false, createdAt = 4)
        fixture.documentMessages.value = listOf(receipt)
        coEvery { fixture.chatMessageRepository.getMessages() } returns listOf(user, command, receipt, partial)
        val model = fixture.createViewModel()
        advanceUntilIdle()
        assertEquals(user.visibleText, model.uiState.value.messages.first().text())
        assertEquals(listOf(R.string.chatbot_response_unavailable.toString(), R.string.chatbot_response_unavailable.toString()),
            model.uiState.value.messages.filterIsInstance<ChatMessage.AI>().map { it.text })
        assertEquals(receipt.visibleText, model.uiState.value.messages.filterIsInstance<ChatMessage.Document>().single().text)
        assertTrue(model.uiState.value.canRetryIncomplete)
        assertTrue(fixture.persistedMessages.isEmpty())
        coVerify { fixture.aiService.replaceChatHistory(listOf(user, receipt)) }
        coVerify(exactly = 0) { fixture.commandOperations.commit(any(), any(), any(), any()) }
    }

    @Test
    fun `unrecognized structured responses do not become conversation text or history context`() = runTest(dispatcher) {
        val fixture = fixture(isPremium = false)
        val raw = "Here is the result: {\"action\":\"unsupported\"}"
        coEvery { fixture.aiService.processCommand("Consulta") } returns AIResult(true, raw)
        val model = fixture.createViewModel()
        advanceUntilIdle()
        model.sendMessage("Consulta")
        advanceUntilIdle()
        assertEquals(R.string.chatbot_response_unavailable.toString(), model.uiState.value.messages.last().text())
        assertEquals(R.string.chatbot_response_unavailable.toString(), fixture.persistedMessages.last().visibleText)
        assertNull(fixture.persistedMessages.last().contextText)
        assertFalse(fixture.persistedMessages.last().includeInContext)
    }

    private fun fixture(isPremium: Boolean): Fixture {
        val aiService = mockk<AIService>()
        val context = mockk<Context>(relaxed = true)
        val chatMessageRepository = mockk<ChatMessageRepository>()
        val premium = MutableStateFlow(isPremium)
        val persistedMessages = mutableListOf<ChatMessageRecord>()
        val documentMessages = MutableStateFlow<List<ChatMessageRecord>>(emptyList())
        val invoiceRepository = mockk<InvoiceRepository>()
        val incomeRepository = mockk<IncomeRepository>()
        val productRepository = mockk<ProductRepository>()
        val exchangeRateProvider = mockk<ExchangeRateProvider>(relaxed = true)
        val currencyPreference = mockk<CurrencyPreference>()
        val invoiceDriveService = mockk<InvoiceDriveService>()
        val invoiceImageStorage = mockk<com.gastos.storage.InvoiceImageStorage>(relaxed = true)
        val saveInvoiceUseCase = mockk<SaveInvoiceUseCase>()
        val commandOperations = mockk<com.gastos.storage.CommandOperationStore>(relaxed = true)
        coEvery { commandOperations.latestPending() } returns null
        coEvery { commandOperations.begin(any(), any()) } coAnswers {
            val uuid = firstArg<String>()
            val text = secondArg<String>()
            if (persistedMessages.none { it.operationUuid == uuid }) persistedMessages.add(ChatMessageRecord(role = "user", visibleText = text, contextText = text, operationUuid = uuid))
            com.gastos.domain.model.CommandOperation(uuid, text)
        }

        every { aiService.isConfigured() } returns true
        every { context.getString(any()) } answers { firstArg<Int>().toString() }
        every { context.getString(any(), any()) } answers { firstArg<Int>().toString() }
        every { context.getString(any(), any(), any()) } answers { firstArg<Int>().toString() }
        every { context.getString(any(), any(), any(), any()) } answers { firstArg<Int>().toString() }
        every { context.getString(any(), any(), any(), any(), any()) } answers { firstArg<Int>().toString() }
        coJustRun { aiService.setPremiumLimits(any()) }
        coJustRun { aiService.replaceChatHistory(any()) }
        coEvery { chatMessageRepository.getMessages() } returns emptyList()
        every { chatMessageRepository.observeDocumentMessages() } returns documentMessages
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
            documentMessages = documentMessages,
            invoiceRepository = invoiceRepository,
            incomeRepository = incomeRepository,
            productRepository = productRepository,
            exchangeRateProvider = exchangeRateProvider,
            currencyPreference = currencyPreference,
            invoiceDriveService = invoiceDriveService,
            remoteSyncOutboxRepository = mockk(relaxed = true),
            invoiceImageStorage = invoiceImageStorage,
            saveInvoiceUseCase = saveInvoiceUseCase,
            commandOperations = commandOperations
        )
    }

    private data class Fixture(
        val context: Context,
        val aiService: AIService,
        val chatMessageRepository: ChatMessageRepository,
        val premium: MutableStateFlow<Boolean>,
        val persistedMessages: MutableList<ChatMessageRecord>,
        val documentMessages: MutableStateFlow<List<ChatMessageRecord>>,
        val invoiceRepository: InvoiceRepository,
        val incomeRepository: IncomeRepository,
        val productRepository: ProductRepository,
        val exchangeRateProvider: ExchangeRateProvider,
        val currencyPreference: CurrencyPreference,
        val invoiceDriveService: InvoiceDriveService,
        val remoteSyncOutboxRepository: RemoteSyncOutboxRepository,
        val invoiceImageStorage: com.gastos.storage.InvoiceImageStorage,
        val saveInvoiceUseCase: SaveInvoiceUseCase,
        val commandOperations: com.gastos.storage.CommandOperationStore
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
            currencyPreference = currencyPreference,
            commandOperations = commandOperations
        )
    }

    private fun ChatMessage.text(): String = when (this) {
        is ChatMessage.User -> text
        is ChatMessage.AI -> text
        is ChatMessage.System -> text
        is ChatMessage.Document -> text
    }
}
