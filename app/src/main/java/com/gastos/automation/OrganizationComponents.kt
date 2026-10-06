package com.gastos.automation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.gastos.R
import com.gastos.domain.model.Category
import com.gastos.domain.model.DocumentKind
import com.gastos.domain.model.TransactionCategories

@Composable
internal fun OrganizationHeader(section: AutomationSection, expanded: Boolean, onClick: () -> Unit) {
    val title: Int = when (section) {
        AutomationSection.CATEGORIES -> R.string.categories_tab
        AutomationSection.RULES -> R.string.rules_tab
        AutomationSection.LIMITS -> R.string.limits_tab
        AutomationSection.WALLET -> R.string.wallet_capture_title
    }
    val subtitle: Int = when (section) {
        AutomationSection.CATEGORIES -> R.string.categories_subtitle
        AutomationSection.RULES -> R.string.rules_subtitle
        AutomationSection.LIMITS -> R.string.limits_subtitle
        AutomationSection.WALLET -> R.string.wallet_subtitle
    }
    val icon: ImageVector = when (section) {
        AutomationSection.CATEGORIES -> Icons.Default.FolderOpen
        AutomationSection.RULES -> Icons.Default.AutoAwesome
        AutomationSection.LIMITS -> Icons.Default.PieChartOutline
        AutomationSection.WALLET -> Icons.Default.Payments
    }
    val state: String = stringResource(if (expanded) R.string.section_expanded else R.string.section_collapsed)
    ListItem(
        headlineContent = { Text(stringResource(title), style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(stringResource(subtitle), style = MaterialTheme.typography.bodySmall) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) },
        trailingContent = { Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).testTag("section-${section.name}")
            .semantics { stateDescription = state }.clickable(role = Role.Button, onClick = onClick)
    )
}

@Composable
internal fun MovementKindSelector(kind: DocumentKind, onSelected: (DocumentKind) -> Unit, enabled: Boolean = true) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        DocumentKind.entries.forEachIndexed { index, item ->
            SegmentedButton(selected = kind == item, onClick = { onSelected(item) }, enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, DocumentKind.entries.size)) {
                Text(stringResource(if (item == DocumentKind.EXPENSE) R.string.expenses_title else R.string.income_title))
            }
        }
    }
}

@Composable
internal fun OrganizationMenu(name: String, enabled: Boolean, actions: List<Pair<Int, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(Icons.Default.MoreVert, stringResource(R.string.organization_options, name))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { open = false; action() })
            }
        }
    }
}

@Composable
internal fun NewItemButton(label: Int, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(label))
    }
}

@Composable
internal fun CategoryPicker(rows: List<Category>, selectedId: String, label: String, enabled: Boolean, onSelected: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(rows.firstOrNull { it.id == selectedId }?.let { TransactionCategories.displayCategory(it.name) }
                    ?: stringResource(R.string.category_unselected))
            }
            Icon(Icons.Default.ExpandMore, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.category_unselected)) }, onClick = { onSelected(""); open = false })
            rows.forEach { row -> DropdownMenuItem(text = {
                Text(TransactionCategories.displayCategory(row.name) + if (row.archived) " · " + stringResource(R.string.archived_label) else "")
            }, onClick = { onSelected(row.id); open = false }) }
        }
    }
}

@Composable
internal fun Disclosure(label: Int, expanded: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val state: String = stringResource(if (expanded) R.string.section_expanded else R.string.section_collapsed)
    Row(Modifier.fillMaxWidth().semantics { stateDescription = state }.clickable(role = Role.Button, onClick = onClick)
        .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
    }
    if (expanded) content()
}
