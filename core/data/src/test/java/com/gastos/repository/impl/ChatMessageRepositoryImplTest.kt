package com.gastos.repository.impl

import com.gastos.data.local.entity.ChatMessageEntity
import com.gastos.domain.model.ChatMessageRecord
import com.gastos.local.dao.ChatMessageDao
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageRepositoryImplTest {
    private val dao = mockk<ChatMessageDao>()
    private val repository = ChatMessageRepositoryImpl(dao)

    @Test
    fun `messages are mapped from Room in DAO order`() = runTest {
        coEvery { dao.getAllMessages() } returns listOf(
            ChatMessageEntity(id = 1, role = "user", visibleText = "Pregunta", createdAt = 10),
            ChatMessageEntity(id = 2, role = "model", visibleText = "Respuesta", createdAt = 20)
        )

        val messages = repository.getMessages()

        assertEquals(listOf("Pregunta", "Respuesta"), messages.map { it.visibleText })
        assertEquals(listOf("user", "model"), messages.map { it.role })
    }

    @Test
    fun `adding a message delegates to transactional insert and trim`() = runTest {
        val record = ChatMessageRecord(
            id = 7,
            role = "model",
            visibleText = "Respuesta",
            contextText = "Contexto",
            includeInContext = false,
            createdAt = 30
        )
        coEvery { dao.insertAndTrim(any()) } returns Unit

        repository.addMessage(record)

        coVerify(exactly = 1) {
            dao.insertAndTrim(
                ChatMessageEntity(
                    id = 7,
                    role = "model",
                    visibleText = "Respuesta",
                    contextText = "Contexto",
                    includeInContext = false,
                    createdAt = 30
                )
            )
        }
    }

    @Test
    fun `clearing history delegates to Room`() = runTest {
        coJustRun { dao.clearAll() }

        repository.clearAll()

        coVerify(exactly = 1) { dao.clearAll() }
    }
}
