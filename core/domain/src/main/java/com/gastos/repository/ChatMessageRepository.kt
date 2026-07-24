package com.gastos.repository

import com.gastos.domain.model.ChatMessageRecord
interface ChatMessageRepository {
    suspend fun getMessages(): List<ChatMessageRecord>
    suspend fun addMessage(message: ChatMessageRecord)
    suspend fun clearAll()
}
