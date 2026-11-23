package com.gastos.feature.invoices

import com.gastos.domain.model.Invoice
import com.gastos.domain.model.TaxEffect
import com.gastos.domain.model.TaxTreatment

/**
 * Línea fiscal visible en el detalle del gasto, sin desplegables.
 *
 * Existe porque el dato puede estar guardado y no verse: la retención vivía en
 * `evidence.document.withholdingAmount` y el tratamiento exento dentro de `taxesJson`, pero el
 * detalle solo enseñaba base, cuota y un resumen plegado. Un valor calculado no puede
 * desaparecer de la pantalla que el autónomo abre al día siguiente.
 */
internal data class InvoiceFiscalLine(
    val base: Double?,
    val vat: Double?,
    val withheld: Double?,
    val nonTaxable: TaxTreatment?
)

internal fun invoiceFiscalLine(invoice: Invoice): InvoiceFiscalLine? {
    val nonTaxable: TaxTreatment? = invoice.taxes.firstOrNull {
        it.treatment == TaxTreatment.EXEMPT || it.treatment == TaxTreatment.OUT_OF_SCOPE
    }?.treatment
    val savedWithholding: Double? = invoice.evidence?.document?.withholdingAmount
    val rowWithholding: Double? = invoice.taxes
        .filter { it.effect == TaxEffect.WITHHOLDING }
        .mapNotNull { it.amount }
        .takeIf { it.isNotEmpty() }
        ?.sum()
    // Un cero guardado es un dato, no un hueco: si el documento dice que no hubo retención, no se
    // sustituye por el porcentaje (el autónomo exento al inicio de actividad marca 15 % y retiene 0).
    val withheld: Double? = when {
        savedWithholding != null -> savedWithholding.takeIf { it > 0.0 }
        rowWithholding != null -> rowWithholding.takeIf { it > 0.0 }
        else -> invoice.irpfPercent.takeIf { it > 0.0 }?.let { rate -> invoice.baseImponible?.times(rate / 100.0) }
    }
    val vat: Double? = if (nonTaxable != null) null else invoice.cuotaIva
    if (invoice.baseImponible == null && vat == null && withheld == null && nonTaxable == null) return null
    return InvoiceFiscalLine(invoice.baseImponible, vat, withheld, nonTaxable)
}
