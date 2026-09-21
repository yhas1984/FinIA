package com.gastos.repository.impl

import com.gastos.data.local.entity.ChatMessageEntity
import com.gastos.domain.model.ChatMessageRecord
import com.gastos.local.dao.ChatMessageDao
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.every
import kotlinx.coroutines.flow.MutableStateFlow
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageRepositoryImplTest {
    private val dao = mockk<ChatMessageDao>()
    private val repository = ChatMessageRepositoryImpl(dao)

    @Test
    fun `document receipt stream forwards committed messages and deletions`() = runTest {
        val messages = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
        every { dao.observeDocumentMessages() } returns messages
        repository.observeDocumentMessages().test {
            assertEquals(emptyList<ChatMessageRecord>(), awaitItem())
            messages.value = listOf(ChatMessageEntity(id = 5, role = "document", visibleText = "Receipt", includeInContext = false, createdAt = 40))
            assertEquals(ChatMessageRecord(id = 5, role = "document", visibleText = "Receipt", includeInContext = false, createdAt = 40), awaitItem().single())
            messages.value = emptyList()
            assertEquals(emptyList<ChatMessageRecord>(), awaitItem())
        }
    }

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
