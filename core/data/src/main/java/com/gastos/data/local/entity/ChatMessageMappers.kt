package com.gastos.data.local.entity

import com.gastos.domain.model.ChatMessageRecord

fun ChatMessageEntity.toDomain(): ChatMessageRecord = ChatMessageRecord(
    id = id,
    role = role,
    visibleText = visibleText,
    contextText = contextText,
    includeInContext = includeInContext,
    createdAt = createdAt
)

fun ChatMessageRecord.toEntity(): ChatMessageEntity = ChatMessageEntity(
    id = id,
    role = role,
    visibleText = visibleText,
    contextText = contextText,
    includeInContext = includeInContext,
    createdAt = createdAt
)
