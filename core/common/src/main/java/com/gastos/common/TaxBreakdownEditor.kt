package com.gastos.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gastos.domain.model.*
import java.util.Locale

@Composable
fun TaxBreakdownEditor(rows: List<TaxFormRow>, currency: String, locale: Locale, onChange: (List<TaxFormRow>) -> Unit, onAdd: () -> Unit) {
    var expanded: Boolean by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (rows.isEmpty()) R.string.taxes_add_breakdown else R.string.taxes_breakdown_count, rows.size))
        }
        if (expanded) {
            Text(stringResource(R.string.taxes_help), style = MaterialTheme.typography.bodySmall)
            rows.forEachIndexed { index, row ->
                fun update(value: TaxFormRow) { onChange(rows.toMutableList().apply { set(index, value) }) }
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(row.name, { update(row.copy(name = it)) }, label = { Text(stringResource(R.string.taxes_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        TaxChoice(row.treatment, TaxTreatment.entries, { update(row.copy(treatment = it)) }) { treatment ->
                            stringResource(when (treatment) {
                                TaxTreatment.TAXABLE -> R.string.taxes_taxable
                                TaxTreatment.ZERO_RATED -> R.string.taxes_zero
                                TaxTreatment.EXEMPT -> R.string.taxes_exempt
                                TaxTreatment.OUT_OF_SCOPE -> R.string.taxes_outside
                                TaxTreatment.UNKNOWN -> R.string.taxes_unknown
                            })
                        }
                        TaxChoice(row.effect, TaxEffect.entries, { update(row.copy(effect = it)) }) {
                            stringResource(if (it == TaxEffect.CHARGE) R.string.taxes_charge else R.string.taxes_withholding)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TaxInput(row.rate, { update(row.copy(rate = it, amount = "")) }, stringResource(R.string.taxes_rate), Modifier.weight(1f))
                            TaxInput(row.base, { update(row.copy(base = it, amount = "")) }, stringResource(R.string.taxes_base), Modifier.weight(1f))
                        }
                        TaxInput(row.amount, { update(row.copy(amount = it)) }, stringResource(R.string.taxes_amount, currency), Modifier.fillMaxWidth())
                        if (row.amount.isBlank()) row.parse(locale, currency)?.amount?.let {
                            Text(stringResource(R.string.taxes_calculated, LocalizedNumbers.format(it, locale), currency))
                        }
                        TextButton(onClick = { onChange(rows.filterIndexed { position, _ -> position != index }) }) { Text(stringResource(R.string.taxes_remove)) }
                    }
                }
            }
            TextButton(onClick = onAdd) { Text(stringResource(R.string.taxes_add)) }
        }
    }
}

@Composable
private fun TaxInput(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier)
}

@Composable
private fun <T> TaxChoice(value: T, options: List<T>, onChange: (T) -> Unit, label: @Composable (T) -> String) {
    var expanded: Boolean by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(label(value)) }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(label(option)) }, onClick = { onChange(option); expanded = false }) }
        }
    }
}

@Composable
fun TaxBreakdownSummary(taxes: List<DocumentTax>, currency: String) {
    var expanded: Boolean by rememberSaveable { mutableStateOf(false) }
    if (taxes.isEmpty()) return
    val locale: Locale = LocalConfiguration.current.locales[0]
    Column {
        TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.taxes_breakdown_count, taxes.size)) }
        if (expanded) taxes.forEach { tax ->
            val treatment: String = stringResource(when (tax.treatment) {
                TaxTreatment.TAXABLE -> R.string.taxes_taxable
                TaxTreatment.ZERO_RATED -> R.string.taxes_zero
                TaxTreatment.EXEMPT -> R.string.taxes_exempt
                TaxTreatment.OUT_OF_SCOPE -> R.string.taxes_outside
                TaxTreatment.UNKNOWN -> R.string.taxes_unknown
            })
            val effect: String = stringResource(if (tax.effect == TaxEffect.CHARGE) R.string.taxes_charge else R.string.taxes_withholding)
            val name: String = formatTaxName(tax.name, tax.rate, stringResource(R.string.taxes_unknown), locale)
            Text("$name · $treatment · $effect", style = MaterialTheme.typography.bodySmall)
            Text("${stringResource(R.string.taxes_base)}: ${tax.base?.let { formatMoney(it, currency) } ?: "—"} · ${tax.amount?.let { formatMoney(it, currency) } ?: "—"}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
