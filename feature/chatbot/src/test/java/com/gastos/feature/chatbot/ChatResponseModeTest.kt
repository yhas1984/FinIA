package com.gastos.feature.chatbot

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatResponseModeTest {
    @Test fun `free is complete and premium is streaming`() {
        assertEquals(ChatResponseMode.FREE_COMPLETE, chatResponseMode(false))
        assertEquals(ChatResponseMode.PREMIUM_STREAM, chatResponseMode(true))
    }
}
