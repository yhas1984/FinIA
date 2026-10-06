package com.gastos.automation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.gastos.R
import com.gastos.domain.model.*

@Composable
internal fun CategoriesSection(
    categories: List<Category>, kind: DocumentKind, onKind: (DocumentKind) -> Unit,
    expandedId: String, onExpand: (String) -> Unit, showArchived: Boolean, onShowArchived: (Boolean) -> Unit,
    busy: Boolean, onCreate: (String) -> Unit, onRename: (Category) -> Unit, onArchive: (Category) -> Unit
) {
    MovementKindSelector(kind, onKind)
    NewItemButton(R.string.new_category, !busy) { onCreate("") }
    val roots = categories.filter { it.kind == kind && it.parentId.isEmpty() && (!it.archived || showArchived) }
    if (roots.isEmpty()) Text(stringResource(R.string.categories_empty), style = MaterialTheme.typography.bodyMedium)
    roots.forEach { parent -> key(parent.id) {
        val children = categories.filter { it.parentId == parent.id && (!it.archived || showArchived) }
        val open = expandedId == parent.id
        val state = stringResource(if (open) R.string.section_expanded else R.string.section_collapsed)
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).testTag("category-${parent.id}").semantics { stateDescription = state }
                    .clickable(role = Role.Button) { onExpand(if (open) "" else parent.id) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(TransactionCategories.displayCategory(parent.name), style = MaterialTheme.typography.titleSmall)
                        Text(if (parent.archived) stringResource(R.string.archived_label)
                            else pluralStringResource(R.plurals.subcategory_count, children.size, children.size),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
                OrganizationMenu(parent.name, !busy, categoryActions(parent, onRename, onArchive))
            }
            if (open) Column(Modifier.padding(start = 16.dp)) {
                if (children.isEmpty()) Text(stringResource(R.string.organization_no_subcategories), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                children.forEach { child -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(child.name, style = MaterialTheme.typography.bodyMedium)
                        if (child.archived) Text(stringResource(R.string.archived_label), style = MaterialTheme.typography.bodySmall)
                    }
                    OrganizationMenu(child.name, !busy, categoryActions(child, onRename, onArchive).filterNot {
                        parent.archived && it.first == R.string.unarchive_category
                    })
                } }
                if (!parent.archived) TextButton(onClick = { onCreate(parent.id) }, enabled = !busy) { Text(stringResource(R.string.add_subcategory)) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        }
    } }
    Row(Modifier.fillMaxWidth().toggleable(showArchived, role = Role.Checkbox, onValueChange = onShowArchived),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(showArchived, null)
        Text(stringResource(R.string.show_archived), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun categoryActions(category: Category, onRename: (Category) -> Unit, onArchive: (Category) -> Unit): List<Pair<Int, () -> Unit>> =
    listOf(R.string.rename_category to { onRename(category) },
        (if (category.archived) R.string.unarchive_category else R.string.archive_category) to { onArchive(category) })
