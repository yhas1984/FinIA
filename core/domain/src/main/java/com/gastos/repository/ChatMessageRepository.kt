package com.gastos.repository

import com.gastos.domain.model.ChatMessageRecord
import kotlinx.coroutines.flow.Flow
interface ChatMessageRepository {
    suspend fun getMessages(): List<ChatMessageRecord>
    fun observeDocumentMessages(): Flow<List<ChatMessageRecord>>
    suspend fun addMessage(message: ChatMessageRecord)
    suspend fun replaceLastIncomplete(message: ChatMessageRecord)
    suspend fun clearAll()
}
