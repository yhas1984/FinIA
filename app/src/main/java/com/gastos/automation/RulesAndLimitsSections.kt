package com.gastos.automation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gastos.R
import com.gastos.domain.model.*
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import com.gastos.feature.dashboard.R as DashboardR

@Composable
internal fun RulesSection(
    categories: List<Category>, rules: List<CategoryRule>, kind: DocumentKind, onKind: (DocumentKind) -> Unit,
    busy: Boolean, onCreate: () -> Unit, onEdit: (CategoryRule) -> Unit,
    onToggle: (CategoryRule) -> Unit, onDelete: (CategoryRule) -> Unit
) {
    MovementKindSelector(kind, onKind)
    NewItemButton(R.string.new_rule, !busy, onCreate)
    Text(stringResource(R.string.rule_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val selected = rules.filter { it.kind == kind }.sortedWith(compareByDescending<CategoryRule> { it.priority }.thenBy { it.merchant })
    if (selected.isEmpty()) Text(stringResource(R.string.rules_empty), style = MaterialTheme.typography.bodyMedium)
    selected.forEach { rule -> key(rule.id) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(rule.merchant, style = MaterialTheme.typography.titleSmall)
                val names = listOfNotNull(categories.firstOrNull { it.id == rule.categoryId }?.let { TransactionCategories.displayCategory(it.name) },
                    categories.firstOrNull { it.id == rule.subcategoryId }?.name)
                Text("→ ${names.joinToString(" / ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val switchLabel = stringResource(R.string.rule_enable, rule.merchant)
            Switch(rule.enabled, { onToggle(rule) }, enabled = !busy, modifier = Modifier.semantics { contentDescription = switchLabel })
            OrganizationMenu(rule.merchant, !busy, listOf(R.string.edit_limit to { onEdit(rule) }, R.string.remove_rule to { onDelete(rule) }))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    } }
}

@Composable
internal fun LimitsSection(
    model: AutomationViewModel, month: String, onMonth: (String) -> Unit, busy: Boolean,
    onCreate: () -> Unit, onEdit: (MonthlyLimit) -> Unit, onDelete: (MonthlyLimit) -> Unit
) {
    val locale = LocalLocale.current.platformLocale
    val progress by remember(month, model) { model.progress(month) }.collectAsStateWithLifecycle(emptyList())
    val selected = YearMonth.parse(month)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = { onMonth(selected.minusMonths(1).toString()) }, enabled = selected.year > 1900 || selected.monthValue > 1) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_month))
        }
        Text(selected.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)), style = MaterialTheme.typography.titleSmall)
        IconButton(onClick = { onMonth(selected.plusMonths(1).toString()) }, enabled = selected.year < 9999 || selected.monthValue < 12) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_month))
        }
    }
    NewItemButton(R.string.new_limit, !busy, onCreate)
    if (progress.isEmpty()) Text(stringResource(R.string.organization_no_limits_hint), style = MaterialTheme.typography.bodyMedium)
    progress.forEach { entry -> key(entry.limit.id) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(TransactionCategories.displayCategory(entry.category), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                OrganizationMenu(entry.category, !busy, listOf(R.string.edit_limit to { onEdit(entry.limit) }, R.string.remove_rule to { onDelete(entry.limit) }))
            }
            Text(stringResource(R.string.limit_spent_of,
                entry.spent?.let { formatMoney(it, entry.limit.currency) } ?: stringResource(DashboardR.string.total_unavailable),
                formatMoney(entry.limit.amount, entry.limit.currency)), style = MaterialTheme.typography.bodyMedium)
            entry.ratio?.let { ratio -> LinearProgressIndicator(progress = { ratio.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) }
            if (entry.partial) Text(stringResource(DashboardR.string.limit_partial, entry.excluded), style = MaterialTheme.typography.bodySmall)
            else entry.remaining?.let { Text(stringResource(if (it >= 0) DashboardR.string.limit_remaining else DashboardR.string.limit_exceeded,
                formatMoney(kotlin.math.abs(it), entry.limit.currency)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        }
    } }
}
