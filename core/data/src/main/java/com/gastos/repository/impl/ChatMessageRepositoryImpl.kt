package com.gastos.repository.impl

import com.gastos.data.local.entity.toDomain
import com.gastos.data.local.entity.toEntity
import com.gastos.local.dao.ChatMessageDao
import com.gastos.domain.model.ChatMessageRecord
import com.gastos.repository.ChatMessageRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatMessageRepositoryImpl @Inject constructor(
    private val dao: ChatMessageDao
) : ChatMessageRepository {
    override suspend fun getMessages(): List<ChatMessageRecord> = dao.getAllMessages().map { it.toDomain() }
    override suspend fun addMessage(message: ChatMessageRecord) {
        dao.insertAndTrim(message.toEntity())
    }
    override suspend fun clearAll() = dao.clearAll()
}
