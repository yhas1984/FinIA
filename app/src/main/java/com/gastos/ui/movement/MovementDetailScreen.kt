package com.gastos.ui.movement

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gastos.common.design.*
import com.gastos.domain.model.formatMoney
import com.gastos.domain.model.PayrollLineType
import com.gastos.feature.incomes.IncomeDetailContent
import com.gastos.feature.invoices.InvoiceDetailContent
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat

@Composable
fun MovementDetailScreen(onBack: () -> Unit, onEdit: (Boolean, Long) -> Unit, model: MovementDetailViewModel = hiltViewModel()) {
    val state by model.uiState.collectAsStateWithLifecycle()
    val locale = LocalLocale.current.platformLocale
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    Scaffold(topBar = { EssentialHeader(stringResource(com.gastos.common.R.string.essential_details), onBack) }) { padding ->
        if (state.loading) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val date = DateFormat.getDateInstance(DateFormat.SHORT, locale) as? SimpleDateFormat ?: SimpleDateFormat("yyyy-MM-dd", locale)
            state.invoice?.let { value ->
                InvoiceDetailContent(value, date, onDelete = model::delete, onEdit = { if (!state.busy) onEdit(false, value.id) },
                    onRetryDrive = model::retryImage, isPremium = state.premium, isUploadingToDrive = state.busy, enabled = !state.busy)
                value.notas?.takeIf(String::isNotBlank)?.let { EssentialSection(stringResource(com.gastos.common.R.string.essential_notes)) { Text(it) } }
            }
            state.income?.let { value ->
                IncomeDetailContent(value, date, onDelete = model::delete, onEdit = { if (!state.busy) onEdit(true, value.id) }, enabled = !state.busy)
                val doc = value.evidence?.document
                val identity = listOf(
                    com.gastos.common.R.string.essential_document_number to doc?.number,
                    com.gastos.common.R.string.essential_issuer_tax_id to doc?.issuerTaxId,
                    com.gastos.common.R.string.essential_recipient_tax_id to doc?.recipientTaxId,
                    com.gastos.common.R.string.essential_worker to doc?.workerId,
                    com.gastos.common.R.string.essential_payroll_reference to doc?.payrollReference,
                    com.gastos.common.R.string.essential_pay_period to doc?.payPeriod,
                    com.gastos.common.R.string.essential_payment_kind to doc?.paymentKind
                ).filter { !it.second.isNullOrBlank() }
                if (identity.isNotEmpty()) EssentialSection(stringResource(com.gastos.common.R.string.essential_document_data)) {
                    identity.forEach { (label, text) ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(text.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (value.totalDevengado != 0.0 || value.totalNeto != 0.0 || doc?.payroll != null || doc?.gross != null || doc?.net != null) EssentialSection(stringResource(com.gastos.common.R.string.essential_payroll), true) {
                    (doc?.gross ?: value.totalDevengado.takeIf { it != 0.0 })?.let { DetailAmount(stringResource(com.gastos.feature.incomes.R.string.gross_amount), formatMoney(it, value.moneda, locale)) }
                    (doc?.net ?: value.totalNeto.takeIf { it != 0.0 })?.let { DetailAmount(stringResource(com.gastos.feature.incomes.R.string.net_amount), formatMoney(it, value.moneda, locale)) }
                    listOf(com.gastos.common.R.string.essential_contribution_base to doc?.contributionBase,
                        com.gastos.common.R.string.essential_social_security to doc?.socialSecurity,
                        com.gastos.common.R.string.essential_withholding to doc?.withholdingAmount,
                        com.gastos.common.R.string.essential_total_deductions to doc?.payroll?.totalDeductions).forEach { (label, amount) ->
                        amount?.let { DetailAmount(stringResource(label), formatMoney(it, value.moneda, locale)) }
                    }
                    doc?.payroll?.let { payroll ->
                        listOfNotNull(payroll.periodStart, payroll.periodEnd).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" – ")) }
                        payroll.lines.groupBy { it.type }.forEach { (type, lines) ->
                            Text(stringResource(when (type) {
                                PayrollLineType.EARNING -> com.gastos.common.R.string.essential_earnings
                                PayrollLineType.DEDUCTION -> com.gastos.common.R.string.essential_deductions
                                PayrollLineType.UNKNOWN -> com.gastos.common.R.string.essential_other_lines
                            }), Modifier.padding(top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            lines.forEach { line -> DetailAmount(line.description.orEmpty(), line.amount?.let { formatMoney(it, value.moneda, locale) }) }
                        }
                    }
                }
                value.notas?.takeIf(String::isNotBlank)?.let { EssentialSection(stringResource(com.gastos.common.R.string.essential_notes)) { Text(it) } }
            }
            val evidence = state.invoice?.evidence ?: state.income?.evidence
            if (evidence?.fieldOrigins?.get("date") == com.gastos.domain.model.DocumentFieldOrigin.CAPTURE)
                Text(stringResource(com.gastos.R.string.document_capture_date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (evidence?.fieldOrigins?.get("currency") == com.gastos.domain.model.DocumentFieldOrigin.PREFERENCE)
                Text(stringResource(com.gastos.R.string.document_preference_currency), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.products.isNotEmpty()) EssentialSection(stringResource(com.gastos.common.R.string.essential_products), true) {
                state.products.forEach { product ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) { Text(product.descripcion); Text("${NumberFormat.getNumberInstance(locale).format(product.cantidad)} × ${formatMoney(product.precioUnitario, state.invoice?.moneda ?: state.income?.moneda ?: "EUR", locale)}", style = MaterialTheme.typography.bodySmall) }
                        Text(formatMoney(product.subtotal, state.invoice?.moneda ?: state.income?.moneda ?: "EUR", locale), Modifier.widthIn(max = 150.dp))
                    }
                    com.gastos.common.TaxBreakdownSummary(product.taxes, state.invoice?.moneda ?: state.income?.moneda ?: "EUR")
                }
            }
            if (state.invoice == null && state.income == null) Text(stringResource(com.gastos.common.R.string.essential_missing))
        }
    }
}

@Composable
private fun DetailAmount(label: String, amount: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        amount?.let { Text(it, Modifier.widthIn(max = 150.dp), style = MaterialTheme.typography.bodyMedium) }
    }
}
