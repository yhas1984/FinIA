package com.gastos.domain.model

import kotlinx.serialization.Serializable

/** Kept separately from the bounded chat history so retries cannot repeat a committed operation. */
@Serializable
data class CommandOperation(
    val uuid: String,
    val text: String,
    val status: String = "PENDING",
    val resultKind: String? = null,
    val resultUuid: String? = null,
    val resultText: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
