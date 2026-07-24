package com.gastos.feature.ai

import com.gastos.domain.model.ChatMessageRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHistoryWindowTest {
    @Test
    fun `free context keeps last three turns and excludes technical messages`() {
        val messages = buildList {
            repeat(5) { turn ->
                add(message("user", "question-$turn"))
                add(message("model", "answer-$turn"))
            }
            add(message("system", "temporary error"))
            add(message("model", "", contextText = ""))
        }

        val selected = selectContextMessages(messages, limitTurns = 3)

        assertEquals(6, selected.size)
        assertEquals("question-2", selected.first().visibleText)
        assertEquals("answer-4", selected.last().visibleText)
    }

    @Test
    fun `premium context keeps last ten turns`() {
        val messages = buildList {
            repeat(12) { turn ->
                add(message("user", "question-$turn"))
                add(message("model", "answer-$turn"))
            }
        }

        val selected = selectContextMessages(messages, limitTurns = 10)

        assertEquals(20, selected.size)
        assertEquals("question-2", selected.first().visibleText)
        assertEquals("answer-11", selected.last().visibleText)
    }

    @Test
    fun `orphan user message is not restored as model context`() {
        val messages = listOf(
            message("user", "failed request"),
            message("user", "successful request"),
            message("model", "successful response")
        )

        val selected = selectContextMessages(messages, limitTurns = 3)

        assertEquals(2, selected.size)
        assertEquals("successful request", selected.first().visibleText)
    }

    private fun message(
        role: String,
        visibleText: String,
        contextText: String? = visibleText
    ): ChatMessageRecord = ChatMessageRecord(
        role = role,
        visibleText = visibleText,
        contextText = contextText
    )
}
