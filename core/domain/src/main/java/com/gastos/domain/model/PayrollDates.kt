package com.gastos.domain.model

import java.text.DateFormatSymbols
import java.text.Normalizer
import java.util.Locale

/** A reference date is selected locally; the model cannot turn an employee code into a ledger date. */
internal object PayrollDates {
    private val languages: List<String> = listOf("en", "es", "ca", "fr", "de", "it", "pt", "nl", "pl", "tr")
    private val months: List<Set<String>> by lazy {
        (0..11).map { month -> languages.flatMap { language ->
            val symbols: DateFormatSymbols = DateFormatSymbols(Locale.forLanguageTag(language))
            listOf(symbols.months[month], symbols.shortMonths[month]).map(::normalize).map { it.trimEnd('.') }
        }.filter { it.length >= 3 }.toSet() }
    }

    fun normalize(source: PayrollDetails, derived: MutableSet<String>): PayrollDetails {
        fun iso(value: String?, field: String): String? {
            val normalized: String? = value?.replace(Regex("^([12])\\.([0-9]{3})(-\\d{2}-\\d{2})$"), "$1$2$3")
            if (value != normalized) derived.add("payroll.$field")
            return normalized
        }
        fun date(value: String?, quote: String?, field: String): String? {
            val normalized: String? = iso(value, field)
            // A long final component is an identifier, not a day. Keep its quote and
            // original extraction, but never turn its prefix into an issue/payment date.
            if (normalized != null && quote != null && !containsDate(quote, normalized) &&
                Regex("(?<!\\d)\\d{4}-\\d{2}-\\d{3,}(?!\\d)").containsMatchIn(quote)) {
                derived.add("payroll.$field")
                return null
            }
            return normalized
        }
        return source.copy(paymentDate = date(source.paymentDate, source.paymentDateText, "paymentDate"),
            issueDate = date(source.issueDate, source.issueDateText, "issueDate"),
            periodStart = iso(source.periodStart, "periodStart"), periodEnd = iso(source.periodEnd, "periodEnd"))
    }

    fun resolve(source: ScannedDocument, issues: MutableList<ReviewIssue>): Pair<String?, PayrollDateBasis?> {
        val payroll: PayrollDetails = requireNotNull(source.payroll)
        if (payroll.dateBasis == PayrollDateBasis.MANUAL) {
            if (DocumentValidator.parseDate(source.date) == null) issues.add(ReviewIssue("date", ReviewReason.INVALID))
            return source.date to PayrollDateBasis.MANUAL
        }
        listOf("paymentDate" to (payroll.paymentDate to payroll.paymentDateText), "issueDate" to (payroll.issueDate to payroll.issueDateText),
            "periodStart" to (payroll.periodStart to source.payPeriod), "periodEnd" to (payroll.periodEnd to source.payPeriod)).forEach { (field, values) ->
            if (values.first != null && !containsDate(values.second, values.first)) issues.add(ReviewIssue("payroll.$field", ReviewReason.INVALID))
        }
        val start: Long? = DocumentValidator.parseDate(payroll.periodStart)
        val end: Long? = DocumentValidator.parseDate(payroll.periodEnd)
        if (start != null && end != null && start > end) issues.add(ReviewIssue("payroll.periodEnd", ReviewReason.INCONSISTENT))
        val selected: Pair<String?, PayrollDateBasis?> = when {
            payroll.paymentDate != null -> payroll.paymentDate to PayrollDateBasis.PAYMENT_DATE
            payroll.issueDate != null -> payroll.issueDate to PayrollDateBasis.ISSUE_DATE
            payroll.periodEnd != null -> payroll.periodEnd to PayrollDateBasis.PERIOD_END
            else -> null to null
        }
        if (DocumentValidator.parseDate(selected.first) == null) issues.add(ReviewIssue("date", ReviewReason.MISSING))
        return selected
    }

    /** Complete numeric dates or a day/month-name/year in the quoted date/period field, never code substrings. */
    private fun containsDate(text: String?, isoDate: String?): Boolean {
        if (text.isNullOrBlank() || DocumentValidator.parseDate(isoDate) == null) return false
        val parts: List<Int> = requireNotNull(isoDate).split('-').map(String::toInt)
        val (year: Int, month: Int, day: Int) = parts
        val source: String = normalize(text).replace(Regex("(?<!\\d)([12])\\.([0-9]{3})(?!\\d)"), "$1$2")
        val separator: String = "[-/.]"
        val numeric: String = "(?:$year${separator}0?$month${separator}0?$day|0?$day${separator}0?$month${separator}$year|0?$month${separator}0?$day${separator}$year)"
        if (Regex("(?<![\\p{L}\\d/-])$numeric(?![\\p{L}\\d/-])").containsMatchIn(source)) return true
        if (!Regex("(?<!\\d)$year(?!\\d)").containsMatchIn(source) || !Regex("(?<!\\d)0?$day(?!\\d)").containsMatchIn(source)) return false
        return months[month - 1].any { name -> Regex("(?<!\\p{L})${Regex.escape(name)}(?!\\p{L})").containsMatchIn(source) }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
}
