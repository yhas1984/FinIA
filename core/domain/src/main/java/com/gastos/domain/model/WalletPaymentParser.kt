package com.gastos.domain.model

import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Currency

/** Only affirmative payment notices with an explicit currency and merchant become expenses. */
@kotlinx.serialization.Serializable
data class WalletPayment(val eventId: String, val merchant: String, val amount: Double, val currency: String, val occurredAt: Long)

object WalletPaymentParser {
    const val PACKAGE: String = "com.google.android.apps.walletnfcrel"
    fun parse(packageName: String, key: String, occurredAt: Long, title: String, body: String): WalletPayment? {
        if (packageName != PACKAGE || key.isBlank() || occurredAt <= 0) return null
        val text = "$title\n$body"
        if (Regex("(?i)pendiente|pending|rechaz|declin|fallid|failed|devolu|refund|cancel|verific|c[oó]digo|\\botp\\b").containsMatchIn(text)) return null
        if (!Regex("(?i)pagaste|has pagado|pago realizado|compra realizada|payment (?:complete|successful)|you paid|paid at").containsMatchIn(text)) return null
        val money = Regex("(?i)(?:(\\b[A-Z]{3}\\b|€|£)\\s*([0-9][0-9.,]*))|(?:([0-9][0-9.,]*)\\s*(\\b[A-Z]{3}\\b|€|£))")
            .findAll(text).toList()
        if (money.size != 1) return null
        val found = money.single()
        if (found.range.first > 0 && text[found.range.first - 1] == '-') return null
        val currency = (found.groupValues[1].ifEmpty { found.groupValues[4] }).uppercase().let { when(it) { "€" -> "EUR"; "£" -> "GBP"; else -> it } }
        if (runCatching { Currency.getInstance(currency) }.isFailure) return null
        val raw = found.groupValues[2].ifEmpty { found.groupValues[3] }
        val value = amount(raw) ?: return null
        val merchant = Regex("(?i)(?:\\ben\\b|\\bat\\b|\\bto\\b)\\s+([^\\n]+)").find(body)?.groupValues?.get(1)?.trim()?.trimEnd('.', ' ')
            ?.takeIf { it.length in 2..100 && !money.any { match -> it.contains(match.value) } } ?: return null
        val id = MessageDigest.getInstance("SHA-256").digest("$packageName|$key|$occurredAt".toByteArray()).joinToString("") { "%02x".format(it) }
        return WalletPayment(id, merchant, value, currency, occurredAt)
    }
    internal fun amount(raw: String): Double? {
        val normalized: String = when {
            Regex("[0-9]+[.,][0-9]{1,2}").matches(raw) -> raw.replace(',', '.')
            Regex("[0-9]{1,3}(?:\\.[0-9]{3})+,[0-9]{2}").matches(raw) -> raw.replace(".", "").replace(',', '.')
            Regex("[0-9]{1,3}(?:,[0-9]{3})+\\.[0-9]{2}").matches(raw) -> raw.replace(",", "")
            Regex("[0-9]+").matches(raw) -> raw
            else -> return null
        }
        return runCatching { BigDecimal(normalized).toDouble() }.getOrNull()?.takeIf { it.isFinite() && it > 0 }
    }
}
