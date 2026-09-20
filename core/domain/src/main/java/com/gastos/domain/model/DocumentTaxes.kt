package com.gastos.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.MathContext
import java.util.Currency

@Serializable
enum class TaxTreatment { TAXABLE, ZERO_RATED, EXEMPT, OUT_OF_SCOPE, UNKNOWN }

@Serializable
enum class TaxEffect { CHARGE, WITHHOLDING }

/** A printed tax component. Bases may overlap: never sum them to obtain the document base. */
@Serializable
data class DocumentTax(
    val name: String? = null,
    val rate: Double? = null,
    val base: Double? = null,
    val amount: Double? = null,
    val treatment: TaxTreatment = TaxTreatment.UNKNOWN,
    val effect: TaxEffect = TaxEffect.CHARGE
)

object DocumentTaxCodec {
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(taxes: List<DocumentTax>): String = json.encodeToString(taxes)
    fun decode(value: String): List<DocumentTax> = json.decodeFromString(value)
}

object DocumentTaxes {
    fun tolerance(currency: String?): BigDecimal {
        val digits: Int = runCatching { Currency.getInstance(currency).defaultFractionDigits }.getOrDefault(2).coerceAtLeast(0)
        return BigDecimal(2).movePointLeft(digits)
    }

    fun decimal(value: Double?): BigDecimal? = value?.takeIf(Double::isFinite)?.let(BigDecimal::valueOf)

    fun total(taxes: List<DocumentTax>, effect: TaxEffect): Double? {
        val components: List<DocumentTax> = taxes.filter { it.effect == effect }
        if (components.any { decimal(it.amount) == null }) return null
        return components.fold(BigDecimal.ZERO) { sum, tax -> sum + decimal(tax.amount)!! }.toDouble().takeIf(Double::isFinite)
    }

    fun singleRate(taxes: List<DocumentTax>): Double? = taxes.filter { it.effect == TaxEffect.CHARGE }
        .singleOrNull()?.takeIf { it.treatment in setOf(TaxTreatment.TAXABLE, TaxTreatment.ZERO_RATED) }?.rate

    /** Preserves uncertainty. A net product price with unidentified taxes has no known gross total. */
    fun lineTax(line: ScannedLine, priceBasis: String?): Double? {
        if (line.taxes.isNotEmpty()) return total(line.taxes, TaxEffect.CHARGE)
        val rate: BigDecimal = decimal(line.vatPercent) ?: return null
        val subtotal: BigDecimal = decimal(line.subtotal) ?: return null
        if (rate.signum() < 0) return null
        return when (priceBasis) {
            "tax_excluded" -> subtotal.multiply(rate).divide(BigDecimal(100)).toDouble()
            "tax_included" -> subtotal.multiply(rate).divide(BigDecimal(100) + rate, MathContext.DECIMAL128).toDouble()
            else -> null
        }
    }

    fun validate(taxes: List<DocumentTax>, currency: String?, prefix: String = "taxes"): List<ReviewIssue> {
        val issues: MutableList<ReviewIssue> = mutableListOf()
        val tolerance: BigDecimal = tolerance(currency)
        taxes.forEachIndexed { index, tax ->
            val field: String = "$prefix.$index"
            mapOf("rate" to tax.rate, "base" to tax.base, "amount" to tax.amount).forEach { (key, value) ->
                if (value != null && (!value.isFinite() || value < 0)) issues.add(ReviewIssue("$field.$key", ReviewReason.INVALID))
            }
            val rate: BigDecimal? = decimal(tax.rate)
            val base: BigDecimal? = decimal(tax.base)
            val amount: BigDecimal? = decimal(tax.amount)
            if (tax.treatment in setOf(TaxTreatment.ZERO_RATED, TaxTreatment.EXEMPT, TaxTreatment.OUT_OF_SCOPE) &&
                (rate?.signum()?.let { it != 0 } == true || amount?.signum()?.let { it != 0 } == true)) {
                issues.add(ReviewIssue(field, ReviewReason.INCONSISTENT))
            }
            if (rate != null && base != null && amount != null &&
                (base * rate / BigDecimal(100) - amount).abs() > tolerance) {
                issues.add(ReviewIssue("$field.amount", ReviewReason.INCONSISTENT))
            }
        }
        return issues
    }

    /** Human-readable original-currency detail, shared by reports and model context. */
    fun describe(taxes: List<DocumentTax>, currency: String): String = taxes.joinToString("; ") { tax ->
        "${tax.name.orEmpty()} [${tax.treatment}, ${tax.effect}] " +
            "rate=${tax.rate?.toString() ?: "?"}%; base=${tax.base?.toString() ?: "?"}; " +
            "amount=${tax.amount?.toString() ?: "?"} $currency"
    }
}
