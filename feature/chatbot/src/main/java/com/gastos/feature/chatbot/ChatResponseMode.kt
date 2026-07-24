package com.gastos.feature.chatbot

enum class ChatResponseMode { FREE_COMPLETE, PREMIUM_STREAM }

internal fun chatResponseMode(isPremium: Boolean): ChatResponseMode = if (isPremium) ChatResponseMode.PREMIUM_STREAM else ChatResponseMode.FREE_COMPLETE
