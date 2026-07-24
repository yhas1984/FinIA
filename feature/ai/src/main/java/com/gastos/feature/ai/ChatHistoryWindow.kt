package com.gastos.feature.ai

import com.gastos.domain.model.ChatMessageRecord

internal fun selectContextMessages(
    messages: List<ChatMessageRecord>,
    limitTurns: Int
): List<ChatMessageRecord> {
    val eligible = messages.filter {
        it.includeInContext &&
            it.role in setOf("user", "model") &&
            (it.contextText ?: it.visibleText).isNotBlank()
    }
    val selectedReversed = mutableListOf<ChatMessageRecord>()
    var index = eligible.lastIndex
    var turns = 0
    while (index >= 1 && turns < limitTurns) {
        val model = eligible[index]
        val user = eligible[index - 1]
        if (user.role == "user" && model.role == "model") {
            selectedReversed += model
            selectedReversed += user
            turns++
            index -= 2
        } else {
            index--
        }
    }
    return selectedReversed.asReversed()
}
