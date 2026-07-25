package com.gastos.feature.chatbot

import com.gastos.domain.model.ChatMessageRecord
import com.gastos.feature.ai.AIResult
import com.gastos.feature.ai.AIService
import com.gastos.repository.ChatMessageRepository
import com.gastos.repository.PremiumStatusProvider
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
        every { fixture.aiService.parseStreamingResult("Respuesta") } returns
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

    private fun fixture(isPremium: Boolean): Fixture {
        val aiService = mockk<AIService>()
        val chatMessageRepository = mockk<ChatMessageRepository>()
        val premium = MutableStateFlow(isPremium)
        val persistedMessages = mutableListOf<ChatMessageRecord>()

        every { aiService.isConfigured() } returns true
        coJustRun { aiService.setPremiumLimits(any()) }
        coJustRun { aiService.replaceChatHistory(any()) }
        coEvery { chatMessageRepository.getMessages() } returns emptyList()
        coEvery { chatMessageRepository.addMessage(capture(persistedMessages)) } returns Unit

        return Fixture(
            aiService = aiService,
            chatMessageRepository = chatMessageRepository,
            premium = premium,
            persistedMessages = persistedMessages
        )
    }

    private data class Fixture(
        val aiService: AIService,
        val chatMessageRepository: ChatMessageRepository,
        val premium: MutableStateFlow<Boolean>,
        val persistedMessages: MutableList<ChatMessageRecord>
    ) {
        fun createViewModel() = ChatbotViewModel(
            aiService = aiService,
            chatMessageRepository = chatMessageRepository,
            premiumStatusProvider = object : PremiumStatusProvider {
                override val isPremium = premium
            },
            voiceRecognitionService = mockk(relaxed = true),
            invoiceRepository = mockk(relaxed = true),
            incomeRepository = mockk(relaxed = true),
            productRepository = mockk(relaxed = true),
            sheetsSyncManager = mockk(relaxed = true),
            invoiceDriveService = mockk(relaxed = true),
            invoiceImageStorage = mockk(relaxed = true),
            saveInvoiceUseCase = mockk(relaxed = true),
            saveIncomeUseCase = mockk(relaxed = true),
            exchangeRateProvider = mockk(relaxed = true),
            currencyPreference = mockk(relaxed = true)
        )
    }

    private fun ChatMessage.text(): String = when (this) {
        is ChatMessage.User -> text
        is ChatMessage.AI -> text
        is ChatMessage.System -> text
    }
}
