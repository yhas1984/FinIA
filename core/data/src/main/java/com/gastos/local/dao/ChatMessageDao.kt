package com.gastos.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.gastos.data.local.entity.ChatMessageEntity

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages ORDER BY createdAt ASC, id ASC")
    suspend fun getAllMessages(): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessageEntity): Long

    @Transaction
    suspend fun insertAndTrim(message: ChatMessageEntity, limit: Int = 200) {
        insert(message)
        trimToLast(limit)
    }

    @Query("SELECT * FROM chat_messages ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun latest(): ChatMessageEntity?

    @Transaction
    suspend fun replaceLastIncomplete(message: ChatMessageEntity) {
        val previous = latest()?.takeIf { it.role == "model_incomplete" }
        insertAndTrim(if (previous != null) message.copy(id = previous.id, createdAt = previous.createdAt) else message)
    }

    @Query("DELETE FROM chat_messages")
    suspend fun clearAll()

    @Query("DELETE FROM chat_messages WHERE id NOT IN (SELECT id FROM chat_messages ORDER BY createdAt DESC, id DESC LIMIT :limit)")
    suspend fun trimToLast(limit: Int)
}
