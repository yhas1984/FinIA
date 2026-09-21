package com.gastos.domain.model

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.util.Currency

@Serializable
enum class PayrollLineType { EARNING, DEDUCTION, UNKNOWN }

@Serializable
enum class PayrollDeductionType { SOCIAL_SECURITY, INCOME_TAX, OTHER, UNKNOWN }

@Serializable
enum class PayrollDateBasis { PAYMENT_DATE, ISSUE_DATE, PERIOD_END, MANUAL }

/** Amounts retain their printed sign within their printed earnings/deductions column. */
@Serializable
data class PayrollLine(
    val description: String? = null,
    val type: PayrollLineType = PayrollLineType.UNKNOWN,
    val amount: Double? = null,
    val quantity: Double? = null,
    val unitRate: Double? = null,
    val deductionType: PayrollDeductionType = PayrollDeductionType.UNKNOWN
)

/** Contribution summaries are informational: they are not additional deductions from net pay. */
@Serializable
data class PayrollDetails(
    val paymentDate: String? = null,
    val paymentDateText: String? = null,
    val issueDate: String? = null,
    val issueDateText: String? = null,
    val periodStart: String? = null,
    val periodEnd: String? = null,
    val dateBasis: PayrollDateBasis? = null,
    val totalDeductions: Double? = null,
    val lines: List<PayrollLine> = emptyList(),
    val linesComplete: Boolean? = null
)

/** Payroll accounting never uses invoice product prices, VAT or sales discounts. */
object PayrollValidator {
    fun validate(source: ScannedDocument): ValidatedDocument {
        val issues: MutableList<ReviewIssue> = mutableListOf()
        val derived: MutableSet<String> = mutableSetOf()
        val details: PayrollDetails? = source.payroll?.let { PayrollDates.normalize(it, derived) }
        if (source.issuer.isNullOrBlank()) issues.add(ReviewIssue("issuer", ReviewReason.MISSING))
        if (source.currency !in SUPPORTED_CURRENCIES) issues.add(ReviewIssue("currency", ReviewReason.INVALID))
        if (number(source.net) == null || source.net!! <= 0) issues.add(ReviewIssue("net", ReviewReason.INVALID))
        mapOf("gross" to source.gross, "contributionBase" to source.contributionBase, "socialSecurity" to source.socialSecurity,
            "withholdingAmount" to source.withholdingAmount).forEach { (field, value) ->
            if (value != null && (number(value) == null || value < 0)) issues.add(ReviewIssue(field, ReviewReason.INVALID))
        }
        if (source.withholdingPercent != null && (!source.withholdingPercent.isFinite() || source.withholdingPercent !in 0.0..100.0))
            issues.add(ReviewIssue("withholdingPercent", ReviewReason.INVALID))
        if (details == null) {
            if (DocumentValidator.parseDate(source.date) == null) issues.add(ReviewIssue("date", ReviewReason.INVALID))
            if (source.lines.isNotEmpty()) issues.add(ReviewIssue("payroll", ReviewReason.INCOMPLETE))
            if (number(source.gross) != null && number(source.net) != null && source.net!! > source.gross!!) issues.add(ReviewIssue("net", ReviewReason.INCONSISTENT))
            return ValidatedDocument(source, issues, derived)
        }
        val tolerance: BigDecimal = BigDecimal(2).movePointLeft(runCatching { Currency.getInstance(source.currency).defaultFractionDigits }.getOrDefault(2).coerceAtLeast(0))
        validateLines(details, tolerance, issues)
        if (source.lines.isNotEmpty()) issues.add(ReviewIssue("payroll.lines", ReviewReason.AMBIGUOUS))
        val date: Pair<String?, PayrollDateBasis?> = PayrollDates.resolve(source.copy(payroll = details), issues)
        if (source.date != date.first || details.dateBasis == PayrollDateBasis.PERIOD_END || date.second == PayrollDateBasis.PERIOD_END) derived.add("date")
        val deductions: Double? = details.totalDeductions ?: sum(details, PayrollLineType.DEDUCTION)?.also { derived.add("payroll.totalDeductions") }
        reconcile(source.gross, sum(details, PayrollLineType.EARNING), "gross", tolerance, issues)
        reconcile(deductions, sum(details, PayrollLineType.DEDUCTION), "payroll.totalDeductions", tolerance, issues)
        val earnings: Double? = source.gross ?: sum(details, PayrollLineType.EARNING)
        if (number(earnings) != null && number(deductions) != null)
            reconcile(source.net, (number(earnings)!! - number(deductions)!!).toDouble(), "net", tolerance, issues)
        val socialSecurity: Double? = deductionTotal(details, PayrollDeductionType.SOCIAL_SECURITY)
        val incomeTax: Double? = deductionTotal(details, PayrollDeductionType.INCOME_TAX)
        if (source.socialSecurity != null && source.socialSecurity > 0 && socialSecurity == null) issues.add(ReviewIssue("socialSecurity", ReviewReason.AMBIGUOUS))
        if (source.withholdingAmount != null && source.withholdingAmount > 0 && incomeTax == null) issues.add(ReviewIssue("withholdingAmount", ReviewReason.AMBIGUOUS))
        reconcile(source.socialSecurity, socialSecurity, "socialSecurity", tolerance, issues)
        reconcile(source.withholdingAmount, incomeTax, "withholdingAmount", tolerance, issues)
        if (source.socialSecurity == null && socialSecurity != null) derived.add("socialSecurity")
        if (source.withholdingAmount == null && incomeTax != null) derived.add("withholdingAmount")
        return ValidatedDocument(source.copy(date = date.first, socialSecurity = source.socialSecurity ?: socialSecurity,
            withholdingAmount = source.withholdingAmount ?: incomeTax, payroll = details.copy(dateBasis = date.second, totalDeductions = deductions)), issues.distinct(), derived)
    }

    private fun validateLines(details: PayrollDetails, tolerance: BigDecimal, issues: MutableList<ReviewIssue>) {
        if (details.linesComplete != true || details.lines.isEmpty()) issues.add(ReviewIssue("payroll.lines", ReviewReason.INCOMPLETE))
        if (details.totalDeductions != null && !details.totalDeductions.isFinite()) issues.add(ReviewIssue("payroll.totalDeductions", ReviewReason.INVALID))
        details.lines.forEachIndexed { index, line ->
            val field: String = "payroll.lines.$index"
            if (line.description.isNullOrBlank()) issues.add(ReviewIssue("$field.description", ReviewReason.MISSING))
            if (line.type == PayrollLineType.UNKNOWN) issues.add(ReviewIssue("$field.type", ReviewReason.AMBIGUOUS))
            if (number(line.amount) == null) issues.add(ReviewIssue("$field.amount", ReviewReason.INVALID))
            listOf("quantity" to line.quantity, "unitRate" to line.unitRate).forEach { (name, value) ->
                if (value != null && !value.isFinite()) issues.add(ReviewIssue("$field.$name", ReviewReason.INVALID))
            }
            if (number(line.quantity) != null && number(line.unitRate) != null)
                reconcile(line.amount, (number(line.quantity)!! * number(line.unitRate)!!).toDouble(), "$field.amount", tolerance, issues)
        }
    }

    private fun sum(details: PayrollDetails, type: PayrollLineType): Double? {
        if (details.linesComplete != true || details.lines.isEmpty() || details.lines.any { it.type == PayrollLineType.UNKNOWN || number(it.amount) == null }) return null
        return details.lines.filter { it.type == type }.fold(BigDecimal.ZERO) { total, line -> total + number(line.amount)!! }.toDouble()
    }

    private fun deductionTotal(details: PayrollDetails, type: PayrollDeductionType): Double? {
        val lines: List<PayrollLine> = details.lines.filter { it.type == PayrollLineType.DEDUCTION && it.deductionType == type }
        if (details.linesComplete != true || lines.isEmpty() || lines.any { number(it.amount) == null }) return null
        return lines.fold(BigDecimal.ZERO) { total, line -> total + number(line.amount)!! }.toDouble()
    }

    private fun reconcile(actual: Double?, expected: Double?, field: String, tolerance: BigDecimal, issues: MutableList<ReviewIssue>) {
        if (number(actual) != null && number(expected) != null && (number(actual)!! - number(expected)!!).abs() > tolerance)
            issues.add(ReviewIssue(field, ReviewReason.INCONSISTENT))
    }

    private fun number(value: Double?): BigDecimal? = value?.takeIf(Double::isFinite)?.let(BigDecimal::valueOf)
}
