package com.gastos.domain.model

import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Currency
import java.util.Locale

/** Only affirmative payment notices with an explicit currency and merchant become expenses. */
@kotlinx.serialization.Serializable
data class WalletPayment(val eventId: String, val merchant: String, val amount: Double, val currency: String, val occurredAt: Long)

object WalletPaymentParser {
    const val PACKAGE: String = "com.google.android.apps.walletnfcrel"
    private val moneyPattern: Regex = Regex("(?i)(?:(\\b[A-Z]{3}\\b|€|£)\\s*([0-9][0-9.,]*))|(?:([0-9][0-9.,]*)\\s*(\\b[A-Z]{3}\\b|€|£))")
    private val cardSuffix: Regex = Regex("(?i)(?:con|with)\\s+[\\p{L}\\p{N}][\\p{L}\\p{N} .’'()/\\-]{1,79}\\s+[•●·*xX]{2,}\\s*[0-9]{4}\\.?")
    private val genericTitles: Set<String> = setOf("google wallet", "google pay", "wallet", "pago", "payment")
    fun parse(packageName: String, key: String, occurredAt: Long, title: String, body: String): WalletPayment? {
        if (packageName != PACKAGE || key.isBlank() || occurredAt <= 0) return null
        val normalizedBody: String = normalizeSpaces(body)
        val normalizedTitle: String = normalizeSpaces(title)
        val text = "$normalizedTitle\n$normalizedBody"
        if (Regex("(?i)pendiente|pending|rechaz|declin|fallid|failed|devolu|refund|cancel|verific|c[oó]digo|\\botp\\b").containsMatchIn(text)) return null
        val compactMerchant: String? = findCompactMerchant(normalizedTitle, normalizedBody)
        if (compactMerchant == null && !Regex("(?i)pagaste|has pagado|pago realizado|compra realizada|payment (?:complete|successful)|you paid|paid at").containsMatchIn(text)) return null
        // The compact Wallet layout puts the merchant in the title, not in a payment sentence.
        // Read its amount from the body so a name such as "Bar 21" is not parsed as currency.
        val paymentText: String = if (compactMerchant != null) normalizedBody else text
        val money = moneyPattern.findAll(paymentText).toList()
        if (money.size != 1) return null
        val found = money.single()
        if (found.range.first > 0 && paymentText[found.range.first - 1] == '-') return null
        val currency = (found.groupValues[1].ifEmpty { found.groupValues[4] }).uppercase(Locale.ROOT).let { when(it) { "€" -> "EUR"; "£" -> "GBP"; else -> it } }
        if (runCatching { Currency.getInstance(currency) }.isFailure) return null
        val raw = found.groupValues[2].ifEmpty { found.groupValues[3] }
        val value = amount(raw) ?: return null
        val merchant = compactMerchant ?: Regex("(?i)(?:\\ben\\b|\\bat\\b|\\bto\\b)\\s+([^\\n]+)").find(normalizedBody)?.groupValues?.get(1)?.trim()?.trimEnd('.', ' ')
            ?.takeIf { it.length in 2..100 && !money.any { match -> it.contains(match.value) } } ?: return null
        val id = MessageDigest.getInstance("SHA-256").digest("$packageName|$key|$occurredAt".toByteArray()).joinToString("") { "%02x".format(it) }
        return WalletPayment(id, merchant, value, currency, occurredAt)
    }
    private fun findCompactMerchant(title: String, body: String): String? {
        val money: MatchResult = moneyPattern.find(body) ?: return null
        if (money.range.first != 0 || !cardSuffix.matches(body.substring(money.range.last + 1).trim())) return null
        return title.takeIf { it.length in 2..100 && '\n' !in it && '\r' !in it && it.lowercase(Locale.ROOT) !in genericTitles }
    }
    private fun normalizeSpaces(text: String): String =
        text.map { character: Char -> if (Character.isSpaceChar(character)) ' ' else character }.joinToString("").trim()
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
