package com.gastos.domain.model

import java.math.BigDecimal

/** Prepares extracted values for storage without requiring complete or reconciled documents. */
object DocumentExtraction {
    fun prepare(evidence: DocumentEvidence): DocumentEvidence {
        val derived: MutableSet<String> = evidence.derivedFields.toMutableSet()
        val document: ScannedDocument = if (evidence.document.kind == "nomina")
            preparePayroll(evidence.document, derived) else prepareInvoice(evidence.document, derived)
        return evidence.copy(document = document, derivedFields = derived)
    }

    fun amount(document: ScannedDocument): Double? =
        (if (document.kind == "nomina") document.net else document.total)?.takeIf(Double::isFinite)

    private fun prepareInvoice(input: ScannedDocument, derived: MutableSet<String>): ScannedDocument {
        val source: ScannedDocument = DocumentDiscounts.normalize(input, derived)
        val completeTaxes: Boolean = source.taxes.isNotEmpty() && source.taxesComplete == true
        val charged: Double? = if (completeTaxes) DocumentTaxes.total(source.taxes, TaxEffect.CHARGE) else null
        val withheld: Double? = if (completeTaxes && source.taxes.any { it.effect == TaxEffect.WITHHOLDING })
            DocumentTaxes.total(source.taxes, TaxEffect.WITHHOLDING) else null
        fun fill(field: String, original: Double?, calculated: Double?): Double? = original ?: calculated?.takeIf(Double::isFinite)?.also { derived.add(field) }
        val tax: Double? = fill("vatAmount", source.vatAmount, charged)
        val retention: Double? = fill("withholdingAmount", source.withholdingAmount, withheld)
        val base: Double? = fill("taxBase", source.taxBase, if (charged != null)
            source.total?.let { it - charged + (retention ?: 0.0) } else null)
        val rate: Double? = if (source.taxes.isNotEmpty()) DocumentTaxes.singleRate(source.taxes) else source.vatPercent
        if (rate != source.vatPercent) derived.add("vatPercent")
        val uniform: Boolean = rate != null && rate in 0.0..100.0 && source.taxes.size <= 1 &&
            source.lines.all { it.vatPercent == null || it.vatPercent == rate } && base != null && tax != null &&
            kotlin.math.abs(base * rate / 100.0 - tax) <= DocumentTaxes.tolerance(source.currency).toDouble()
        val lines: List<ScannedLine> = source.lines.mapIndexed { index, line ->
            val quantity: Double? = line.quantity?.takeIf { it.isFinite() && it != 0.0 }
            val price: Double? = fill("lines.$index.unitPrice", line.unitPrice,
                if (quantity != null) line.subtotal?.div(quantity) else null)
            val subtotal: Double? = fill("lines.$index.subtotal", line.subtotal,
                if (quantity != null) price?.times(quantity) else null)
            val lineRate: Double? = if (line.taxes.isNotEmpty()) DocumentTaxes.singleRate(line.taxes) else
                fill("lines.$index.vatPercent", line.vatPercent, rate?.takeIf { uniform })
            line.copy(unitPrice = price, subtotal = subtotal, vatPercent = lineRate)
        }
        val normalized: ScannedDocument = source.copy(taxBase = base, vatAmount = tax, withholdingAmount = retention, vatPercent = rate, lines = lines)
        val basis: String? = normalized.priceBasis ?: inferPriceBasis(normalized)?.also { derived.add("priceBasis") }
        return normalized.copy(priceBasis = basis)
    }

    private fun inferPriceBasis(source: ScannedDocument): String? {
        if (source.linesComplete != true || source.lines.isEmpty() || source.lines.any { DocumentTaxes.decimal(it.subtotal) == null }) return null
        val base: BigDecimal = DocumentTaxes.decimal(source.taxBase) ?: return null
        val total: BigDecimal = DocumentTaxes.decimal(source.total) ?: return null
        val tax: BigDecimal = DocumentTaxes.decimal(source.vatAmount) ?: return null
        val withheld: BigDecimal = DocumentTaxes.decimal(source.withholdingAmount) ?: BigDecimal.ZERO
        val tolerance: BigDecimal = DocumentTaxes.tolerance(source.currency)
        if (tax <= tolerance || (base + tax - withheld - total).abs() > tolerance) return null
        val sum: BigDecimal = source.lines.fold(BigDecimal.ZERO) { value, line -> value + requireNotNull(DocumentTaxes.decimal(line.subtotal)) } -
            (DocumentTaxes.decimal(source.discount) ?: BigDecimal.ZERO)
        val included: Boolean = (sum - total - withheld).abs() <= tolerance
        val excluded: Boolean = (sum - base).abs() <= tolerance
        return when { included && !excluded -> "tax_included"; excluded && !included -> "tax_excluded"; else -> null }
    }

    private fun preparePayroll(source: ScannedDocument, derived: MutableSet<String>): ScannedDocument {
        val details: PayrollDetails = source.payroll?.let { PayrollDates.normalize(it, derived) } ?: return source
        val dated: ScannedDocument = source.copy(payroll = details)
        val date: Pair<String?, PayrollDateBasis?> = PayrollDates.resolve(dated, mutableListOf())
        val selectedDate: String? = date.first?.takeIf { DocumentValidator.parseDate(it) != null } ?: source.date
        if (selectedDate != source.date) derived.add("date")
        fun sum(type: PayrollDeductionType?): Double? {
            if (details.linesComplete != true) return null
            val rows: List<PayrollLine> = details.lines.filter { it.type == PayrollLineType.DEDUCTION && (type == null || it.deductionType == type) }
            if (rows.isEmpty() || rows.any { it.amount?.isFinite() != true }) return null
            return rows.fold(BigDecimal.ZERO) { value, line -> value + BigDecimal.valueOf(requireNotNull(line.amount)) }.toDouble().takeIf(Double::isFinite)
        }
        fun fill(field: String, original: Double?, calculated: Double?): Double? = original ?: calculated?.also { derived.add(field) }
        return dated.copy(date = selectedDate,
            socialSecurity = fill("socialSecurity", source.socialSecurity, sum(PayrollDeductionType.SOCIAL_SECURITY)),
            withholdingAmount = fill("withholdingAmount", source.withholdingAmount, sum(PayrollDeductionType.INCOME_TAX)),
            payroll = details.copy(dateBasis = if (selectedDate == date.first) date.second else details.dateBasis,
                totalDeductions = fill("payroll.totalDeductions", details.totalDeductions, sum(null))))
    }
}

class UnreadableDocumentAmountException : IllegalArgumentException("DOCUMENT_AMOUNT_UNREADABLE")
