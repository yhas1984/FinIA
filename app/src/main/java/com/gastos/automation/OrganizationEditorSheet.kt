package com.gastos.automation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.gastos.R
import com.gastos.common.SaveState
import com.gastos.domain.model.Category
import com.gastos.domain.model.RuleMatch
import com.gastos.domain.model.TransactionCategories

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrganizationEditorSheet(
    draft: OrganizationDraft, categories: List<Category>, result: SaveState, dirty: Boolean,
    onChange: (OrganizationDraft) -> Unit, onClose: () -> Unit, onSave: () -> Unit
) {
    var discard by rememberSaveable { mutableStateOf(false) }
    val saving: Boolean = result == SaveState.Saving
    val currentSaving by rememberUpdatedState(saving)
    val currentDirty by rememberUpdatedState(dirty)
    val currentClose by rememberUpdatedState(onClose)
    val requestClose: () -> Unit = {
        if (!currentSaving) { if (currentDirty) discard = true else currentClose() }
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { value ->
        if (value != SheetValue.Hidden) true
        else if (currentSaving) false
        else if (currentDirty) { discard = true; false } else true
    })
    LaunchedEffect(result) { if (result == SaveState.Success) onClose() }
    ModalBottomSheet(onDismissRequest = requestClose, sheetState = sheet, modifier = Modifier.testTag("organization-editor")) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(editorTitle(draft)), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = requestClose, enabled = !saving) { Icon(Icons.Default.Close, stringResource(R.string.automation_dismiss)) }
            }
            when (draft.editor) {
                OrganizationEditor.CATEGORY -> CategoryEditor(draft, categories, !saving, onChange, onSave)
                OrganizationEditor.RULE -> RuleEditor(draft, categories, !saving, onChange)
                OrganizationEditor.LIMIT -> LimitEditor(draft, categories, !saving, onChange)
            }
            (result as? SaveState.Error)?.let { error ->
                Text(error.message, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("editor-error").semantics { liveRegion = LiveRegionMode.Polite })
            }
            Button(onClick = onSave, enabled = !saving && result != SaveState.Success, modifier = Modifier.fillMaxWidth().testTag("editor-save")) {
                if (saving) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(stringResource(if (saving) R.string.organization_saving else R.string.automation_save))
            }
            Spacer(Modifier.height(24.dp))
        }
        BackHandler(onBack = requestClose)
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false },
        title = { Text(stringResource(R.string.discard_title)) }, text = { Text(stringResource(R.string.discard_description)) },
        confirmButton = { TextButton(onClick = { discard = false; onClose() }) { Text(stringResource(R.string.discard_changes)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.continue_editing)) } })
}

private fun editorTitle(draft: OrganizationDraft): Int = when (draft.editor) {
    OrganizationEditor.CATEGORY -> if (draft.originalId.isNotEmpty()) R.string.rename_category
        else if (draft.parentId.isNotEmpty()) R.string.add_subcategory else R.string.new_category
    OrganizationEditor.RULE -> if (draft.originalId.isEmpty()) R.string.new_rule else R.string.edit_rule
    OrganizationEditor.LIMIT -> if (draft.originalId.isEmpty()) R.string.new_limit else R.string.edit_limit
}

@Composable
private fun CategoryEditor(draft: OrganizationDraft, categories: List<Category>, enabled: Boolean,
    onChange: (OrganizationDraft) -> Unit, onSave: () -> Unit) {
    if (draft.originalId.isEmpty() && draft.parentId.isEmpty()) MovementKindSelector(draft.kind, { onChange(draft.copy(kind = it)) }, enabled)
    if (draft.parentId.isNotEmpty()) Text(stringResource(R.string.subcategory_parent,
        categories.firstOrNull { it.id == draft.parentId }?.let { TransactionCategories.displayCategory(it.name) }.orEmpty()),
        style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(draft.name, { onChange(draft.copy(name = it)) }, label = { Text(stringResource(R.string.automation_name)) },
        enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("editor-name"),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { onSave() }))
}

@Composable
private fun RuleEditor(draft: OrganizationDraft, categories: List<Category>, enabled: Boolean, onChange: (OrganizationDraft) -> Unit) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    MovementKindSelector(draft.kind, { onChange(draft.copy(kind = it, parentId = "", childId = "")) }, enabled)
    OutlinedTextField(draft.merchant, { onChange(draft.copy(merchant = it)) }, label = { Text(stringResource(R.string.automation_merchant)) },
        enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("editor-merchant"))
    val roots = categories.filter { it.kind == draft.kind && it.parentId.isEmpty() && (!it.archived || it.id == draft.parentId) }
    CategoryPicker(roots, draft.parentId, stringResource(R.string.automation_category), enabled) { onChange(draft.copy(parentId = it, childId = "")) }
    if (draft.parentId.isNotEmpty()) CategoryPicker(categories.filter { it.parentId == draft.parentId && (!it.archived || it.id == draft.childId) },
        draft.childId, stringResource(R.string.optional_subcategory), enabled) { onChange(draft.copy(childId = it)) }
    Disclosure(R.string.advanced_options, advanced, { advanced = !advanced }) {
        EditorCheckbox(R.string.rule_contains, draft.match == RuleMatch.CONTAINS, enabled) {
            onChange(draft.copy(match = if (it) RuleMatch.CONTAINS else RuleMatch.EXACT))
        }
        OutlinedTextField(draft.priority, { onChange(draft.copy(priority = it)) }, label = { Text(stringResource(R.string.rule_priority)) },
            enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    }
}

@Composable
private fun LimitEditor(draft: OrganizationDraft, categories: List<Category>, enabled: Boolean, onChange: (OrganizationDraft) -> Unit) {
    val context = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }
    val latestDraft by rememberUpdatedState(draft)
    val latestChange by rememberUpdatedState(onChange)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        latestChange(latestDraft.copy(notify = granted))
    }
    CategoryPicker(categories.filter { it.kind == com.gastos.domain.model.DocumentKind.EXPENSE && it.parentId.isEmpty() && (!it.archived || it.id == draft.parentId) },
        draft.parentId, stringResource(R.string.automation_category), enabled) { onChange(draft.copy(parentId = it)) }
    OutlinedTextField(draft.month, { onChange(draft.copy(month = it)) }, label = { Text(stringResource(R.string.limit_month)) },
        enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(draft.amount, { onChange(draft.copy(amount = it)) }, label = { Text(stringResource(R.string.limit_amount)) },
        enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("editor-amount"),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    OutlinedTextField(draft.currency, { onChange(draft.copy(currency = it)) }, label = { Text(stringResource(R.string.limit_currency)) },
        enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
    EditorCheckbox(R.string.limit_repeat, draft.repeat, enabled) { onChange(draft.copy(repeat = it)) }
    EditorCheckbox(R.string.limit_notify, draft.notify, enabled) { checked ->
        if (checked && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else { denied = false; onChange(draft.copy(notify = checked)) }
    }
    if (denied) Text(stringResource(R.string.notifications_denied), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun EditorCheckbox(label: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null, enabled = enabled)
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
    }
}
