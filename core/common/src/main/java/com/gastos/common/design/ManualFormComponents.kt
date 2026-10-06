package com.gastos.common.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.gastos.common.*
import com.gastos.common.R
import java.util.Locale

val LocalManualInputEnabled = staticCompositionLocalOf { true }

@Composable
fun ManualFormSection(title: String, section: ManualSection, errors: Map<ManualField, String>, attempt: Int,
    summary: String? = null, initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    LaunchedEffect(attempt) { if (errors.keys.any { it.section == section }) expanded = true }
    val state = stringResource(if (expanded) R.string.essential_expanded else R.string.essential_collapsed)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth().semantics { stateDescription = state }.clickable(enabled = LocalManualInputEnabled.current, role = Role.Button) { expanded = !expanded }
            .heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
        if (expanded) Column(Modifier.padding(bottom = EssentialLayout.screenPadding),
            verticalArrangement = Arrangement.spacedBy(EssentialLayout.fieldSpacing), content = content)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
    }
}

@Composable
fun ManualErrorTarget(field: ManualField, errors: Map<ManualField, String>, attempt: Int,
    modifier: Modifier = Modifier, content: @Composable (Modifier, String?) -> Unit) {
    val focus = remember { FocusRequester() }
    val intoView = remember { BringIntoViewRequester() }
    val error = errors[field]
    LaunchedEffect(attempt) {
        if (error != null && errors.keys.firstOrNull() == field) {
            withFrameNanos { }
            focus.requestFocus()
            intoView.bringIntoView()
        }
    }
    Column(modifier.bringIntoViewRequester(intoView)) {
        content(Modifier.focusRequester(focus), error)
    }
}

@Composable
fun ManualTextField(value: String, onChange: (String) -> Unit, label: String,
    field: ManualField? = null, errors: Map<ManualField, String> = emptyMap(), attempt: Int = 0,
    numeric: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth()) {
    val input: @Composable (Modifier, String?) -> Unit = { target, error ->
        OutlinedTextField(value, onChange, enabled = LocalManualInputEnabled.current, label = { Text(label) }, modifier = target.fillMaxWidth(),
            isError = error != null, supportingText = error?.let { { Text(it) } }, singleLine = true,
            shape = EssentialLayout.fieldShape, colors = essentialFieldColors(),
            textStyle = if (field == ManualField.AMOUNT) MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
                else MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text))
    }
    if (field == null) input(modifier, null) else ManualErrorTarget(field, errors, attempt, modifier, input)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualChoice(value: String, label: String, options: List<String>, onChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(), error: String? = null) {
    val enabled = LocalManualInputEnabled.current
    var open by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) open = false }
    ExposedDropdownMenuBox(open, { if (enabled) open = it }, modifier) {
        OutlinedTextField(value, {}, enabled = enabled, label = { Text(label) }, readOnly = true, isError = error != null,
            shape = EssentialLayout.fieldShape, colors = essentialFieldColors(),
            supportingText = error?.let { { Text(it) } }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(), singleLine = true)
        ExposedDropdownMenu(open, { open = false }) {
            options.forEach { option -> DropdownMenuItem(enabled = enabled, text = { Text(option) }, onClick = { onChange(option); open = false }) }
        }
    }
}

@Composable
fun manualTaxSummary(rows: List<TaxFormRow>, rate: String, locale: Locale): String {
    if (rows.size > 1) return stringResource(R.string.manual_multiple_taxes)
    val known = if (rows.isNotEmpty()) LocalizedNumbers.parse(rows.first().rate, locale) else LocalizedNumbers.parse(rate, locale)
    return known?.takeIf { it.isFinite() && it in 0.0..100.0 }?.let { LocalizedNumbers.format(it, locale) + " %" } ?: stringResource(R.string.manual_unspecified)
}
