package com.gastos.feature.ai

import java.math.BigDecimal
import java.math.MathContext
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal const val COMMAND_AMOUNT_TOLERANCE: Double = 0.02

internal class InvalidCommandProductsException : IllegalArgumentException("Invalid command products")

internal data class ExplicitProductPricing(
    val statedAmount: Double,
    val quantity: Double,
    val unitPrice: Double,
    val total: Double
) {
    fun isCompatible(value: Double): Boolean =
        listOf(statedAmount, unitPrice, total, statedAmount * quantity).any { candidate: Double ->
            abs(candidate - value) <= COMMAND_AMOUNT_TOLERANCE
        }
}

/** Resolve only a single named product with one explicit quantity and currency amount. */
internal object CommandProductPricing {
    private const val NUMBER: String = "[0-9]+(?:[.,][0-9]+)?"
    private const val MONEY_NUMBER: String = "[0-9]+(?:[.,][0-9]{1,2})?"
    private val quantityPattern: Regex = Regex("(?<![\\p{L}\\d.,/+\\-])($NUMBER)\\s+([\\p{L}]+)\\b")
    private val unitPattern: Regex = Regex("\\b(?:cada (?:uno|una)|por unidad|la unidad|unitari[oa]|each|apiece|per (?:unit|item))\\b|\\bc/u\\b|/\\s*(?:uds?|unidad(?:es)?|unit|item)\\b")
    private val totalPattern: Regex = Regex("\\b(?:ambos|ambas|en total|total|both|together|altogether)\\b")
    private val multipleItemsPattern: Regex = Regex("\\b(?:y|and)\\b|\\+")
    private val adjustmentPattern: Regex = Regex("\\b(?:descuento|rebaja|discount|rebate|propina|tip|retencion|withholding|sin iva|mas iva|before tax|plus tax|tax excluded)\\b")

    fun resolve(original: String, currency: String, productName: String, quantity: Double): ExplicitProductPricing? {
        val normalized: String = normalize(original)
        if (multipleItemsPattern.containsMatchIn(normalized) || adjustmentPattern.containsMatchIn(normalized)) return null
        val amountMatch: MatchResult = amountPattern(currency).findAll(normalized).toList().singleOrNull() ?: return null
        val quantities: List<MatchResult> = quantityPattern.findAll(normalized)
            .filter { match: MatchResult -> match.range.first !in amountMatch.range }.toList()
        val quantityMatch: MatchResult = quantities.singleOrNull() ?: return null
        val names: Set<String> = Regex("[\\p{L}]+").findAll(normalize(productName))
            .map { match: MatchResult -> match.value.removeSuffix("s") }.toSet()
        if (quantityMatch.groupValues[2].removeSuffix("s") !in names) return null
        val statedQuantity: BigDecimal = decimal(quantityMatch.groupValues[1])
        if (statedQuantity.toDouble() != quantity || quantity <= 0.0) throw InvalidCommandProductsException()
        val statedAmount: BigDecimal = decimal(amountMatch.groupValues[1].ifEmpty { amountMatch.groupValues[2] })
        if (statedAmount.signum() <= 0) return null
        val isPerUnit: Boolean = resolvePriceBasis(normalized, quantityMatch, amountMatch) ?: return null
        val total: BigDecimal = if (isPerUnit) statedAmount * statedQuantity else statedAmount
        val unitPrice: BigDecimal = if (isPerUnit) statedAmount else statedAmount.divide(statedQuantity, MathContext.DECIMAL128)
        if (!total.toDouble().isFinite() || !unitPrice.toDouble().isFinite()) throw InvalidCommandProductsException()
        return ExplicitProductPricing(statedAmount.toDouble(), quantity, unitPrice.toDouble(), total.toDouble())
    }

    private fun resolvePriceBasis(original: String, quantity: MatchResult, amount: MatchResult): Boolean? {
        val product: String = Regex.escape(quantity.groupValues[2].removeSuffix("s"))
        val hasUnitPrice: Boolean = unitPattern.containsMatchIn(original) ||
            Regex("\\b(?:cada|por|per)\\s+$product(?:s)?\\b").containsMatchIn(original)
        val hasTotalPrice: Boolean = totalPattern.containsMatchIn(original)
        if (hasUnitPrice && hasTotalPrice) throw InvalidCommandProductsException()
        val prefix: String = original.take(amount.range.first)
        val hasUnitPrefix: Boolean = quantity.range.first < amount.range.first && Regex("\\b(?:a|at)\\s*$").containsMatchIn(prefix)
        if (hasUnitPrice || (hasUnitPrefix && !hasTotalPrice)) return true
        if (hasTotalPrice || Regex("\\b(?:por|for|en)\\s*$").containsMatchIn(prefix)) return false
        return null
    }

    private fun amountPattern(currency: String): Regex {
        val markers: String = when (currency.uppercase(Locale.ROOT)) {
            "EUR" -> "eur|euros?|€"
            "USD" -> "usd|dollars?|\\$"
            "GBP" -> "gbp|pounds?|£"
            else -> Regex.escape(currency.lowercase(Locale.ROOT))
        }
        return Regex("(?<![\\p{L}\\d.,/+\\-])(?:($MONEY_NUMBER)\\s*(?:$markers)(?!\\p{L})|(?:$markers)\\s*($MONEY_NUMBER)(?![\\p{L}\\d.,]))")
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private fun decimal(value: String): BigDecimal = BigDecimal(value.replace(',', '.'))
}
