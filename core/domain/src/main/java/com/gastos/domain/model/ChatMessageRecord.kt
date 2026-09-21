package com.gastos.domain.model

data class ChatMessageRecord(
    val id: Long = 0,
    val role: String,
    val visibleText: String,
    val contextText: String? = null,
    val includeInContext: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val operationUuid: String? = null,
    val documentUuid: String? = null,
    val documentKind: String? = null
)
