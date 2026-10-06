package com.gastos.feature.backup

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.gastos.domain.model.*
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
internal fun ReportFilters(categories: List<Pair<String?, String?>>, initial: ReportFilter, onChange: (ReportFilter, Boolean) -> Unit) {
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT) }
    var start by rememberSaveable { mutableStateOf(initial.startInclusive?.let { formatter.format(java.util.Date(it)) }.orEmpty()) }
    var end by rememberSaveable { mutableStateOf(initial.endExclusive?.let { formatter.format(java.util.Date(it - 1)) }.orEmpty()) }
    var kind by rememberSaveable { mutableStateOf(initial.kind) }
    var category by rememberSaveable { mutableStateOf(initial.category) }
    var child by rememberSaveable { mutableStateOf(initial.subcategory) }
    val startDate = DocumentValidator.parseDate(start)
    val endDate = DocumentValidator.parseDate(end)
    val endExclusive = endDate?.let { Calendar.getInstance().apply { timeInMillis = it; add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis }
    val valid = (start.isBlank() || startDate != null) && (end.isBlank() || endDate != null) && (startDate == null || endExclusive == null || startDate < endExclusive)
    LaunchedEffect(start, end, kind, category, child) { onChange(ReportFilter(startDate, endExclusive, kind, category, child), valid) }
    OutlinedTextField(start, { start = it }, label = { Text(stringResource(R.string.report_start)) }, singleLine = true, isError = start.isNotBlank() && startDate == null)
    OutlinedTextField(end, { end = it }, label = { Text(stringResource(R.string.report_end)) }, singleLine = true, isError = end.isNotBlank() && endDate == null)
    ReportChoice(stringResource(R.string.report_type), listOf(stringResource(R.string.csv_type_expense), stringResource(R.string.csv_type_income)), kind?.let { stringResource(if (it == DocumentKind.EXPENSE) R.string.csv_type_expense else R.string.csv_type_income) }) { index -> kind = index?.let { DocumentKind.entries[it] } }
    val parents = categories.mapNotNull { it.first }.distinct()
    ReportChoice(stringResource(R.string.csv_header_category), parents, category) { index -> category = index?.let { parents[it] }; child = null }
    val children = categories.filter { it.first == category && category != null }.mapNotNull { it.second }.distinct()
    ReportChoice(stringResource(R.string.csv_header_subcategory), children, child) { index -> child = index?.let { children[it] } }
    if (!valid) Text(stringResource(R.string.report_invalid_period), color = MaterialTheme.colorScheme.error)
}

@Composable
private fun ReportChoice(label: String, options: List<String>, selected: String?, choose: (Int?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("$label: ${selected ?: stringResource(R.string.report_all_records)}") }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.report_all_records)) }, onClick = { choose(null); open = false })
            options.forEachIndexed { index, value -> DropdownMenuItem(text = { Text(TransactionCategories.displayCategory(value)) }, onClick = { choose(index); open = false }) }
        }
    }
}
