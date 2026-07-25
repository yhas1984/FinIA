package com.gastos.domain.model

data class ChatMessageRecord(
    val id: Long = 0,
    val role: String,
    val visibleText: String,
    val contextText: String? = null,
    val includeInContext: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
