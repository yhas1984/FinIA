package com.gastos.feature.chatbot

/** Internal commands are never conversation text, even when validation or persistence fails. */
internal object ChatResponsePresentation {
    private val actionField: Regex = Regex("\"action\"\\s*:")

    fun isStructured(text: String): Boolean {
        val prefix: String = text.trimStart()
        return prefix.startsWith("{") || prefix.startsWith("[") || prefix.startsWith("```") ||
            actionField.containsMatchIn(prefix)
    }

    fun visiblePartial(text: String): String? = text.takeIf { it.isNotBlank() && !isStructured(it) }
}
