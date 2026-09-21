package com.gastos.domain.model

import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Reclassifies an explicit aggregate discount; never repairs an unidentified negative product. */
internal object DocumentDiscounts {
    private val aggregateLabels: Set<String> = setOf(
        "descuento total", "total descuento", "total discount", "discount total", "total discounts",
        "remise totale", "total remise", "rabatt gesamt", "gesamtrabatt", "sconto totale",
        "desconto total", "totale korting"
    )

    fun normalize(source: ScannedDocument, derived: MutableSet<String>): ScannedDocument {
        if (source.kind == "nomina" || source.linesComplete != true || source.lines.size < 2) return source
        val candidates: List<IndexedValue<ScannedLine>> = source.lines.withIndex().filter {
            normalizeLabel(it.value.description) in aggregateLabels && isDiscount(it.value, source.currency)
        }
        val candidate: IndexedValue<ScannedLine> = candidates.singleOrNull() ?: return source
        val amount: BigDecimal = requireNotNull(DocumentTaxes.decimal(candidate.value.subtotal)).negate()
        val declared: BigDecimal? = DocumentTaxes.decimal(source.discount)
        // A second, different discount is ambiguous. Do not subtract a repeated summary twice.
        if (source.discount != null && (declared == null || declared.signum() < 0 ||
                (declared - amount).abs() > DocumentTaxes.tolerance(source.currency))) return source
        derived.add("discount")
        derived.add("lines")
        return source.copy(discount = declared?.takeIf { it.signum() > 0 }?.toDouble() ?: amount.toDouble(),
            lines = source.lines.filterIndexed { index, _ -> index != candidate.index })
    }

    private fun isDiscount(line: ScannedLine, currency: String?): Boolean {
        val quantity: BigDecimal = DocumentTaxes.decimal(line.quantity) ?: return false
        val price: BigDecimal = DocumentTaxes.decimal(line.unitPrice) ?: return false
        val subtotal: BigDecimal = DocumentTaxes.decimal(line.subtotal) ?: return false
        if (quantity.signum() <= 0 || price.signum() >= 0 || subtotal.signum() >= 0 ||
            (quantity * price - subtotal).abs() > DocumentTaxes.tolerance(currency)) return false
        if (line.vatPercent?.let { !it.isFinite() || it !in 0.0..100.0 } == true) return false
        if (line.taxes.any { it.effect != TaxEffect.CHARGE || it.base?.let { value -> value > 0 } == true ||
                it.amount?.let { value -> value > 0 } == true }) return false
        return DocumentTaxes.validate(line.taxes.map { it.copy(base = it.base?.let(::abs),
            amount = it.amount?.let(::abs)) }, currency).isEmpty()
    }

    private fun normalizeLabel(value: String?): String = Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "").lowercase(Locale.ROOT).trim().trimEnd(':').trim().replace("\\s+".toRegex(), " ")
}
