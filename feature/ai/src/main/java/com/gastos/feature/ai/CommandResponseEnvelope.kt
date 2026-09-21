package com.gastos.feature.ai

import org.json.JSONObject
import org.json.JSONTokener

/** Accept a complete standalone command, including Gemini's optional Markdown wrapper. */
internal object CommandResponseEnvelope {
    fun parse(text: String): JSONObject? {
        val trimmed: String = text.trim()
        val candidate: String = when {
            trimmed.startsWith("{") -> trimmed
            Regex("^```(?:json)?\\s*\\{", RegexOption.IGNORE_CASE).containsMatchIn(trimmed) -> {
                val match: MatchResult = requireNotNull(Regex("^```(?:json)?\\s*([\\s\\S]*?)\\s*```$", RegexOption.IGNORE_CASE).matchEntire(trimmed)) {
                    "Incomplete command response"
                }
                match.groupValues[1].trim()
            }
            else -> return null
        }
        val tokens: JSONTokener = JSONTokener(candidate)
        val result: JSONObject = tokens.nextValue() as? JSONObject ?: error("Invalid command response")
        require(tokens.nextClean() == '\u0000') { "Unexpected content after command" }
        return result
    }
}
