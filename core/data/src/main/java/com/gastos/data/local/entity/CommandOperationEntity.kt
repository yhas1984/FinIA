package com.gastos.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.gastos.domain.model.CommandOperation

@Entity(tableName = "command_operations")
data class CommandOperationEntity(@PrimaryKey val uuid: String, val text: String,
    val status: String, val resultKind: String?, val resultUuid: String?, val resultText: String?, val createdAt: Long)

fun CommandOperationEntity.toDomain(): CommandOperation = CommandOperation(uuid, text, status, resultKind, resultUuid, resultText, createdAt)
fun CommandOperation.toEntity(): CommandOperationEntity = CommandOperationEntity(uuid, text, status, resultKind, resultUuid, resultText, createdAt)
