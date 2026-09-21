package com.gastos.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.MathContext
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Currency
import java.util.Locale

@Serializable
enum class OcrProfile(val thinkingLevel: String) { FAST("low"), THOROUGH("medium") }

/** Nullable values are intentional: an unread value is not a financial zero. */
@Serializable
data class ScannedLine(
    val description: String? = null,
    val quantity: Double? = null,
    val unitPrice: Double? = null,
    val subtotal: Double? = null,
    val vatPercent: Double? = null,
    val taxes: List<DocumentTax> = emptyList()
)

@Serializable
data class ScannedDocument(
    val kind: String? = null,
    val country: String? = null,
    val currency: String? = null,
    val date: String? = null,
    val number: String? = null,
    val issuer: String? = null,
    val issuerTaxId: String? = null,
    val recipientTaxId: String? = null,
    val category: String? = null,
    val subcategory: String? = null,
    val total: Double? = null,
    val taxBase: Double? = null,
    val vatAmount: Double? = null,
    val vatPercent: Double? = null,
    val withholdingPercent: Double? = null,
    val withholdingAmount: Double? = null,
    val discount: Double? = null,
    val priceBasis: String? = null,
    val gross: Double? = null,
    val net: Double? = null,
    val contributionBase: Double? = null,
    val socialSecurity: Double? = null,
    val payrollReference: String? = null,
    val workerId: String? = null,
    val payPeriod: String? = null,
    val paymentKind: String? = null,
    val lines: List<ScannedLine> = emptyList(),
    val linesComplete: Boolean? = null,
    val taxes: List<DocumentTax> = emptyList(),
    val taxesComplete: Boolean? = null,
    val payroll: PayrollDetails? = null
)

@Serializable
data class DocumentEvidence(
    val document: ScannedDocument,
    val sourceSha256: String? = null,
    val originalExtraction: String? = null,
    val correctedFields: Set<String> = emptySet(),
    val fieldEdits: Map<String, String> = emptyMap(),
    val derivedFields: Set<String> = emptySet(),
    val invalidFields: Set<String> = emptySet(),
    /** Candidate UUID and version explicitly confirmed as a distinct document. */
    val distinctFrom: Set<String> = emptySet()
)

object DocumentEvidenceCodec {
    val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(evidence: DocumentEvidence): String = json.encodeToString(evidence)
    fun decode(value: String?): DocumentEvidence? = value?.let {
        runCatching { json.decodeFromString<DocumentEvidence>(it) }.getOrNull()
    }
}

enum class ReviewReason { MISSING, INVALID, INCONSISTENT, AMBIGUOUS, INCOMPLETE }
data class ReviewIssue(val field: String, val reason: ReviewReason)
data class ValidatedDocument(
    val document: ScannedDocument,
    val issues: List<ReviewIssue>,
    val derivedFields: Set<String>
)

/** Checks arithmetic only where the document explicitly identifies comparable amounts. */
object DocumentValidator {
    private val kinds: Set<String> = setOf("nomina", "factura_recibida", "factura_emitida", "ticket", "recibo")

    fun validate(evidence: DocumentEvidence): ValidatedDocument {
        val result: ValidatedDocument = validate(evidence.document)
        val legacyPayroll: List<ReviewIssue> = if (evidence.document.kind == "nomina" && evidence.originalExtraction != null && evidence.document.payroll == null)
            listOf(ReviewIssue("payroll", ReviewReason.INCOMPLETE)) else emptyList()
        return result.copy(issues = (result.issues + legacyPayroll + evidence.invalidFields.map { ReviewIssue(it, ReviewReason.INVALID) }).distinct())
    }

    fun parseDate(value: String?): Long? {
        if (value == null || !Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) return null
        val format: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false }
        val position: ParsePosition = ParsePosition(0)
        return format.parse(value, position)?.takeIf { position.index == value.length }?.time
    }

    fun validate(input: ScannedDocument): ValidatedDocument {
        if (input.kind == "nomina") return PayrollValidator.validate(input)
        val issues: MutableList<ReviewIssue> = mutableListOf()
        val derived: MutableSet<String> = mutableSetOf()
        fun issue(field: String, reason: ReviewReason) { issues.add(ReviewIssue(field, reason)) }
        val source: ScannedDocument = normalizeTaxes(DocumentDiscounts.normalize(input, derived), issues, derived)
        if (source.kind !in kinds) issue("kind", ReviewReason.AMBIGUOUS)
        if (source.issuer.isNullOrBlank()) issue("issuer", ReviewReason.MISSING)
        if (parseDate(source.date) == null) issue("date", if (source.date.isNullOrBlank()) ReviewReason.MISSING else ReviewReason.INVALID)
        if (source.currency !in SUPPORTED_CURRENCIES) issue("currency", ReviewReason.INVALID)
        val payroll: Boolean = source.kind == "nomina"
        val amount: Double? = if (payroll) source.net else source.total
        if (amount == null || !amount.isFinite() || amount <= 0) issue(if (payroll) "net" else "total", ReviewReason.INVALID)
        val numbers: Map<String, Double?> = mapOf("taxBase" to source.taxBase, "vatAmount" to source.vatAmount,
            "withholdingAmount" to source.withholdingAmount, "discount" to source.discount,
            "gross" to source.gross, "contributionBase" to source.contributionBase, "socialSecurity" to source.socialSecurity)
        numbers.forEach { (key, value) -> if (value != null && (!value.isFinite() || value < 0)) issue(key, ReviewReason.INVALID) }
        mapOf("vatPercent" to source.vatPercent, "withholdingPercent" to source.withholdingPercent).forEach { (key, value) ->
            if (value != null && (!value.isFinite() || value < 0 || (value > 100 && (key != "vatPercent" || source.taxes.isEmpty())))) issue(key, ReviewReason.INVALID)
        }
        val tolerance: BigDecimal = tolerance(source.currency)
        val lines: List<ScannedLine> = source.lines.mapIndexed { index, line ->
            val prefix: String = "lines.$index"
            if (line.description.isNullOrBlank()) issue("$prefix.description", ReviewReason.MISSING)
            if (line.quantity == null || !line.quantity.isFinite() || line.quantity <= 0) issue("$prefix.quantity", ReviewReason.INVALID)
            val quantity: BigDecimal? = decimal(line.quantity)?.takeIf { it > BigDecimal.ZERO }
            var price: Double? = line.unitPrice
            var subtotal: Double? = line.subtotal
            if (price == null && subtotal != null && quantity != null) {
                price = decimal(subtotal)?.divide(quantity, MathContext.DECIMAL128)?.toDouble()
                derived.add("$prefix.unitPrice")
            }
            if (subtotal == null && price != null && quantity != null) {
                subtotal = decimal(price)?.multiply(quantity)?.toDouble()
                derived.add("$prefix.subtotal")
            }
            if (price == null || !price.isFinite() || price < 0) issue("$prefix.unitPrice", ReviewReason.INVALID)
            if (subtotal == null || !subtotal.isFinite() || subtotal < 0) issue("$prefix.subtotal", ReviewReason.INVALID)
            if (quantity != null && decimal(price) != null && decimal(subtotal) != null &&
                (quantity * decimal(price)!! - decimal(subtotal)!!).abs() > tolerance) issue("$prefix.subtotal", ReviewReason.INCONSISTENT)
            // Inheritance requires an explicit uniform rate on every other readable line.
            val uniform: Boolean = source.taxes.size <= 1 && decimal(source.vatPercent) != null && source.vatPercent!! in 0.0..100.0 && source.lines.all { it.vatPercent == null || it.vatPercent == source.vatPercent } &&
                source.taxBase != null && source.vatAmount != null && decimal(source.taxBase) != null && decimal(source.vatAmount) != null &&
                (decimal(source.taxBase)!! * decimal(source.vatPercent)!! / BigDecimal(100) - decimal(source.vatAmount)!!).abs() <= tolerance
            val vat: Double? = if (line.taxes.isNotEmpty()) DocumentTaxes.singleRate(line.taxes) else
                line.vatPercent ?: source.vatPercent?.takeIf { uniform }?.also { derived.add("$prefix.vatPercent") }
            if (vat != null && (!vat.isFinite() || vat < 0 || (vat > 100 && line.taxes.isEmpty()))) issue("$prefix.vatPercent", ReviewReason.INVALID)
            issues.addAll(DocumentTaxes.validate(line.taxes, source.currency, "$prefix.taxes"))
            line.copy(unitPrice = price, subtotal = subtotal, vatPercent = vat)
        }
        if (!payroll && source.linesComplete != true) issue("lines", ReviewReason.INCOMPLETE)
        // A missing label can be derived only if exactly one interpretation reconciles
        // the complete rows and printed tax block. Never overwrite an explicit label.
        val priceBasis: String? = source.priceBasis ?: derivePriceBasis(source, lines, tolerance)?.also { derived.add("priceBasis") }
        if (lines.isNotEmpty() && priceBasis !in setOf("tax_included", "tax_excluded")) issue("priceBasis", ReviewReason.AMBIGUOUS)
        if (!payroll && listOf(source.taxBase, source.vatAmount, source.total).all { decimal(it) != null }) {
            val expected: BigDecimal = decimal(source.taxBase)!! + decimal(source.vatAmount)!! - (decimal(source.withholdingAmount) ?: BigDecimal.ZERO)
            if ((expected - decimal(source.total)!!).abs() > tolerance) issue("total", ReviewReason.INCONSISTENT)
        }
        if (!payroll && lines.isNotEmpty() && lines.all { decimal(it.subtotal) != null }) {
            val sum: BigDecimal = lines.fold(BigDecimal.ZERO) { acc, line -> acc + decimal(line.subtotal)!! } - (decimal(source.discount) ?: BigDecimal.ZERO)
            val target: BigDecimal? = when (priceBasis) {
                "tax_excluded" -> decimal(source.taxBase)
                "tax_included" -> decimal(source.total)?.plus(decimal(source.withholdingAmount) ?: BigDecimal.ZERO)
                else -> null
            }
            if (target != null && (sum - target).abs() > tolerance) issue("lines", ReviewReason.INCONSISTENT)
            if ((source.discount == null || source.discount == 0.0) && decimal(source.vatAmount) != null && lines.all { DocumentTaxes.lineTax(it, priceBasis) != null }) {
                val lineTax: BigDecimal = lines.fold(BigDecimal.ZERO) { acc, line ->
                    acc + decimal(DocumentTaxes.lineTax(line, priceBasis))!!
                }
                if ((lineTax - decimal(source.vatAmount)!!).abs() > tolerance) issue("vatAmount", ReviewReason.INCONSISTENT)
            }
        }
        if (payroll && decimal(source.gross) != null && decimal(source.net) != null && source.net!! > source.gross!!) issue("net", ReviewReason.INCONSISTENT)
        return ValidatedDocument(source.copy(lines = lines, priceBasis = priceBasis), issues.distinct(), derived)
    }

    private fun normalizeTaxes(source: ScannedDocument, issues: MutableList<ReviewIssue>, derived: MutableSet<String>): ScannedDocument {
        issues.addAll(DocumentTaxes.validate(source.taxes, source.currency))
        if (source.taxes.isEmpty()) return source
        if (source.taxesComplete != true) issues.add(ReviewIssue("taxes", ReviewReason.INCOMPLETE))
        val charged: Double? = DocumentTaxes.total(source.taxes, TaxEffect.CHARGE)
        val retained: Double? = if (source.taxes.any { it.effect == TaxEffect.WITHHOLDING })
            DocumentTaxes.total(source.taxes, TaxEffect.WITHHOLDING) else source.withholdingAmount
        if (charged == null || source.taxes.any { it.amount == null }) issues.add(ReviewIssue("taxes.amount", ReviewReason.MISSING))
        val tolerance: BigDecimal = tolerance(source.currency)
        if (charged != null && decimal(source.vatAmount) != null &&
            (decimal(charged)!! - decimal(source.vatAmount)!!).abs() > tolerance) {
            issues.add(ReviewIssue("vatAmount", ReviewReason.INCONSISTENT))
        }
        if (source.taxes.any { it.effect == TaxEffect.WITHHOLDING } && retained != null && decimal(source.withholdingAmount) != null &&
            (decimal(retained)!! - decimal(source.withholdingAmount)!!).abs() > tolerance) {
            issues.add(ReviewIssue("withholdingAmount", ReviewReason.INCONSISTENT))
        }
        if (source.taxesComplete != true || charged == null) return source.copy(vatPercent = null)
        val base: Double? = source.taxBase ?: decimal(source.total)?.let {
            (it - decimal(charged)!! + (decimal(retained) ?: BigDecimal.ZERO)).toDouble()
        }?.also { derived.add("taxBase") }
        if (source.vatAmount == null) derived.add("vatAmount")
        if (source.vatPercent != DocumentTaxes.singleRate(source.taxes)) derived.add("vatPercent")
        if (source.withholdingAmount == null && retained != null) derived.add("withholdingAmount")
        return source.copy(taxBase = base, vatAmount = source.vatAmount ?: charged,
            withholdingAmount = source.withholdingAmount ?: retained, vatPercent = DocumentTaxes.singleRate(source.taxes))
    }

    private fun derivePriceBasis(source: ScannedDocument, lines: List<ScannedLine>, tolerance: BigDecimal): String? {
        if (source.linesComplete != true || lines.isEmpty() || lines.any { decimal(it.subtotal) == null }) return null
        val base: BigDecimal = decimal(source.taxBase) ?: return null
        val tax: BigDecimal = decimal(source.vatAmount) ?: return null
        val total: BigDecimal = decimal(source.total) ?: return null
        val withholding: BigDecimal = decimal(source.withholdingAmount) ?: BigDecimal.ZERO
        if (tax <= tolerance || (base + tax - withholding - total).abs() > tolerance) return null
        val sum: BigDecimal = lines.fold(BigDecimal.ZERO) { acc, line -> acc + decimal(line.subtotal)!! } - (decimal(source.discount) ?: BigDecimal.ZERO)
        val included: Boolean = (sum - total - withholding).abs() <= tolerance
        val excluded: Boolean = (sum - base).abs() <= tolerance
        return when {
            included && !excluded -> "tax_included"
            excluded && !included -> "tax_excluded"
            else -> null
        }
    }

    private fun decimal(value: Double?): BigDecimal? = value?.takeIf(Double::isFinite)?.let(BigDecimal::valueOf)
    private fun tolerance(currency: String?): BigDecimal {
        val digits: Int = runCatching { Currency.getInstance(currency).defaultFractionDigits }.getOrDefault(2).coerceAtLeast(0)
        return BigDecimal(2).movePointLeft(digits)
    }
}
