package com.gastos.common.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gastos.common.R
import com.gastos.domain.model.ConversionSummary
import com.gastos.domain.model.formatMoney
import java.text.DateFormat
import java.time.*
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EssentialHeader(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(title = { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
        maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.essential_back)) } } },
        actions = actions, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
}

@Composable
fun EssentialRow(title: String, subtitle: String?, icon: ImageVector, onClick: () -> Unit, trailing: @Composable () -> Unit = {
    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        trailing()
    }
}

@Composable
fun MovementRow(
    title: String,
    category: String,
    date: Long,
    amount: Double,
    currency: String,
    isIncome: Boolean,
    onEdit: () -> Unit,
    onClick: () -> Unit
) {
    val locale = LocalLocale.current.platformLocale
    val subtitle = "$category · ${DateFormat.getDateInstance(DateFormat.SHORT, locale).format(Date(date))}"
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        BoxWithConstraints(
            Modifier.weight(1f).clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = 64.dp).padding(vertical = 12.dp, horizontal = 4.dp)
        ) {
            if (maxWidth < 260.dp || LocalDensity.current.fontScale >= 1.3f) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MovementLabels(title, subtitle)
                    MovementAmount(amount, currency, isIncome)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MovementLabels(title, subtitle, Modifier.weight(1f))
                    MovementAmount(amount, currency, isIncome, Modifier.widthIn(max = 150.dp))
                }
            }
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.manual_edit_movement, title),
                tint = MaterialTheme.colorScheme.primary)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
}

@Composable
private fun MovementLabels(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MovementAmount(amount: Double, currency: String, isIncome: Boolean, modifier: Modifier = Modifier) {
    Text((if (isIncome) "+" else "−") + formatMoney(amount, currency, LocalLocale.current.platformLocale),
        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        color = if (isIncome) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
        modifier = modifier)
}

@Composable
fun MovementHeading(title: String, date: String, category: String, amount: Double, currency: String,
    isIncome: Boolean, source: String? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text((if (isIncome) "+" else "") + formatMoney(amount, currency, LocalLocale.current.platformLocale),
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
            color = if (isIncome) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
        Text("$date · $category", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        source?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun MoneySummary(title: String, amount: Double?, currency: String, summary: ConversionSummary?, onRefresh: () -> Unit,
    action: @Composable () -> Unit = {}) {
    val locale = LocalLocale.current.platformLocale
    Column(Modifier.fillMaxWidth().padding(horizontal = EssentialLayout.screenPadding, vertical = EssentialLayout.smallSpacing), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(EssentialLayout.smallSpacing)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(amount?.let { formatMoney(it, currency, locale) } ?: stringResource(R.string.essential_unavailable),
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            }
            action()
        }
        if (summary?.isPartial == true) {
            var expanded by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(if (summary.isUnavailable) R.string.essential_unavailable else R.string.essential_partial))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (expanded) {
                summary.excluded.forEach { Text("${it.description}: ${formatMoney(it.amount, it.currency, locale)}", style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = onRefresh) { Text(stringResource(R.string.essential_update_rates)) }
            }
        }
    }
}

@Composable
fun EssentialSection(title: String, initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val expansionLabel = stringResource(if (expanded) R.string.essential_expanded else R.string.essential_collapsed)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().semantics { stateDescription = expansionLabel }.clickable(role = Role.Button) { expanded = !expanded }.heightIn(min = 56.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
        if (expanded) content()
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
    }
}

/** Date picker UTC values are converted to local calendar days before filtering stored timestamps. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodSelector(start: Long?, end: Long?, onChange: (Long?, Long?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val locale = LocalLocale.current.platformLocale
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val label = if (start == null) stringResource(R.string.essential_all_periods) else
            DateFormat.getDateInstance(DateFormat.SHORT, locale).format(Date(start)) + " – " + DateFormat.getDateInstance(DateFormat.SHORT, locale).format(Date(end ?: start))
        FilterChip(selected = true, onClick = { open = true }, label = { Text(label, maxLines = 2) }, leadingIcon = { Icon(Icons.Default.DateRange, null, Modifier.size(18.dp)) })
    }
    if (open) {
        val zone = ZoneId.systemDefault()
        fun pickerValue(value: Long?): Long? = value?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        val state = rememberDateRangePickerState(initialSelectedStartDateMillis = pickerValue(start), initialSelectedEndDateMillis = pickerValue(end))
        DatePickerDialog(onDismissRequest = { open = false }, confirmButton = {
            TextButton(enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null, onClick = {
                fun localValue(value: Long?): Long? = value?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli() }
                onChange(localValue(state.selectedStartDateMillis), localValue(state.selectedEndDateMillis)); open = false
            }) { Text(stringResource(R.string.essential_apply)) }
        }, dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.essential_cancel)) } }) {
            Column {
                TextButton(onClick = { onChange(null, null); open = false }, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.essential_all_periods))
                }
                DateRangePicker(state, Modifier.weight(1f, fill = false).heightIn(max = 460.dp))
            }
        }
    }
}
