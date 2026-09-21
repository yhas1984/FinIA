package com.gastos.di

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.MainActivity
import com.gastos.data.local.entity.ChatMessageEntity
import com.gastos.feature.chatbot.ChatMessage
import com.gastos.feature.chatbot.ChatbotScreen
import com.gastos.feature.chatbot.ChatbotViewModel
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the actual parser and restored UI without executing historical commands. */
@RunWith(AndroidJUnit4::class)
class ChatJsonRegressionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun invalidAndUnsupportedCommandsReturnReadableErrors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = EntryPointAccessors.fromApplication(context, LiveAccountTestEntryPoint::class.java)
        val invalidDate = app.reader().parseStreamingResult(
            "```json\n{\"action\":\"add_expense\",\"descripcion\":\"SYNTHETIC\",\"total\":9,\"fecha\":\"2026-02-31\"}\n```",
            "Registra un gasto el 31/02/2026")
        assertFalse(invalidDate.success)
        assertNull(invalidDate.invoice)
        assertEquals(context.getString(com.gastos.feature.ai.R.string.ai_invalid_date), invalidDate.message)
        for (raw in listOf("{\"action\":\"unsupported\"}", "{\"action\":", "{\"response\":\"\"}")) {
            val result = app.reader().parseStreamingResult(raw, "Synthetic query")
            assertFalse(result.success)
            assertEquals(context.getString(com.gastos.feature.ai.R.string.ai_invalid_response), result.message)
            assertNull(result.invoice)
            assertNull(result.income)
        }
    }

    @Test fun historicalJsonIsHiddenWithoutReplayingOrDeletingHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = EntryPointAccessors.fromApplication(context, LiveAccountTestEntryPoint::class.java)
        val before = runBlocking { app.snapshots().snapshot() }
        val raw = "```json\n{\"action\":\"add_expense\",\"total\":9,\"fecha\":\"2026-02-31\"}\n```\n\nIncomplete response"
        val id = runBlocking { app.database().chatMessageDao().insert(ChatMessageEntity(
            role = "model_incomplete", visibleText = raw, contextText = "Synthetic invalid date", includeInContext = false)) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var chat: ChatbotViewModel
                scenario.onActivity { activity ->
                    chat = ViewModelProvider(activity)[ChatbotViewModel::class.java]
                    activity.setContent { MaterialTheme { ChatbotScreen(viewModel = chat) } }
                }
                val safeText = context.getString(com.gastos.feature.chatbot.R.string.chatbot_response_unavailable)
                compose.waitUntil(30_000) { !chat.uiState.value.isProcessing &&
                    chat.uiState.value.messages.lastOrNull().let { it is ChatMessage.AI && it.text == safeText } }
                compose.onAllNodesWithText(safeText).onLast().assertIsDisplayed()
                assertTrue(chat.uiState.value.canRetryIncomplete)
                assertTrue(chat.uiState.value.messages.filterIsInstance<ChatMessage.AI>().none { it.text.contains("\"action\"") })
            }
            val after = runBlocking { app.snapshots().snapshot() }
            assertEquals(before.invoices, after.invoices)
            assertEquals(before.incomes, after.incomes)
            assertEquals(before.products, after.products)
            assertEquals(raw, runBlocking { app.database().chatMessageDao().getAllMessages() }.single { it.id == id }.visibleText)
        } finally {
            runBlocking { app.database().openHelper.writableDatabase.execSQL("DELETE FROM chat_messages WHERE id = ?", arrayOf(id)) }
        }
    }
}
