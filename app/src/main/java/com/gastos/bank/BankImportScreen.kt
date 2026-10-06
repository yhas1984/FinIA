package com.gastos.bank

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gastos.R
import com.gastos.domain.model.*
import java.text.DateFormat
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankImportScreen(onBack: () -> Unit, onOpen: (String, String) -> Unit,
    importedUri: android.net.Uri? = null, onConsumed: () -> Unit = {}, model: BankImportViewModel = hiltViewModel()) {
    val data by model.data.collectAsStateWithLifecycle()
    val table by model.table.collectAsStateWithLifecycle()
    val preview by model.preview.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val accountId by model.accountId.collectAsStateWithLifecycle()
    val batchId by model.batchId.collectAsStateWithLifecycle()
    val editingBatch by model.editingBatch.collectAsStateWithLifecycle()
    val delimiter by model.delimiter.collectAsStateWithLifecycle()
    val headerLine by model.headerLine.collectAsStateWithLifecycle()
    val sourceFileAvailable by model.sourceFileAvailable.collectAsStateWithLifecycle()
    val operation by model.operation.collectAsStateWithLifecycle()
    val rectification by model.rectification.collectAsStateWithLifecycle()
    val mappingJson by model.mapping.collectAsStateWithLifecycle()
    val mapping = remember(mappingJson) { AutomationCodec.json.decodeFromString<BankMapping>(mappingJson) }
    var newAccount by rememberSaveable { mutableStateOf(false) }
    var accountName by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf(model.defaultCurrency) }
    var accountCount by rememberSaveable { mutableIntStateOf(0) }
    var excluded by rememberSaveable { mutableStateOf(false) }
    var previewIdentity by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmCreate by rememberSaveable { mutableStateOf(false) }
    var confirmUndoId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmReopenId by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> model.read(uri) } }
    LaunchedEffect(importedUri) { importedUri?.let { model.read(it, onConsumed) } }
    LaunchedEffect(data.bank.accounts.size) { if (newAccount && data.bank.accounts.size > accountCount) { newAccount = false; accountName = "" } }
    LaunchedEffect(table, mappingJson) {
        table?.let { current ->
            val identity = "${current.hash}:${current.headerLine}:${current.delimiter}:$mappingJson"
            if (previewIdentity.isNotEmpty() && previewIdentity != identity) excluded = false
            previewIdentity = identity
        }
    }
    val rows = data.bank.transactions.filter { it.batchId == batchId }
    val selected = data.bank.transactions.firstOrNull { it.id == selectedId }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.bank_title)) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.bank_back)) }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.bank_intro), style = MaterialTheme.typography.bodyMedium) }
            item { BankOperationStatus(operation, model::cancelOperation) }
            error?.let { value -> item { BankError(value) } }
            item {
                BankChoice(stringResource(R.string.bank_account), accountId,
                    data.bank.accounts.map { it.id to "${it.name} · ${it.currency}" }, !busy && editingBatch.isEmpty(), model::selectAccount)
                if (editingBatch.isEmpty()) TextButton(onClick = { accountCount = data.bank.accounts.size; newAccount = true; model.clearError() }, enabled = !busy) { Text(stringResource(R.string.bank_new_account)) }
            }
            if (table == null) {
                item { Button(onClick = { picker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) },
                    enabled = !busy && accountId.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bank_pick)) } }
            } else {
                val current = table!!
                item {
                    if (sourceFileAvailable) {
                        BankFileFormat(current, delimiter, headerLine, !busy, model::format)
                    } else if (editingBatch.isNotEmpty()) {
                        Text(stringResource(R.string.bank_saved_format_hint), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { picker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) }, enabled = !busy) {
                            Text(stringResource(R.string.bank_choose_original))
                        }
                    }
                }
                item {
                    Text(stringResource(if (editingBatch.isEmpty()) R.string.bank_columns else R.string.bank_edit_configuration), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.bank_sign_hint), style = MaterialTheme.typography.bodySmall)
                    BankColumns(current, mapping, !busy, model::changeMapping)
                    error?.let { BankError(it) }
                    Button(onClick = model::preview, enabled = !busy && accountId.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bank_preview)) }
                    TextButton(onClick = model::cancelFile, enabled = !busy) { Text(stringResource(R.string.bank_cancel_file)) }
                }
                if (preview.isNotEmpty()) {
                    item {
                        Text(stringResource(R.string.bank_preview_count, preview.count { it.error == null }, preview.count { it.error != null }))
                        if (preview.any { it.error != null }) {
                            BankCheck(stringResource(R.string.bank_exclude_invalid), excluded, !busy) { excluded = it }
                        }
                        Button(onClick = { if (editingBatch.isEmpty()) model.import(excluded) else model.requestRectification(excluded) }, enabled = !busy && preview.any { it.transaction != null } && (excluded || preview.none { it.error != null }),
                            modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (editingBatch.isEmpty()) R.string.bank_stage else R.string.bank_review_rectification)) }
                        Text(stringResource(if (editingBatch.isEmpty()) R.string.bank_stage_hint else R.string.bank_rectification_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    items(preview, key = { "preview:${it.line}" }) { value ->
                        Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.padding(12.dp)) {
                                Text(stringResource(R.string.bank_line, value.line), style = MaterialTheme.typography.labelSmall)
                                value.transaction?.let { BankRowText(it) }
                                value.error?.let { BankError(it) }
                            }
                        }
                    }
                }
            }
            if (table == null && data.bank.batches.isNotEmpty()) {
                item {
                    BankChoice(stringResource(R.string.bank_statements), batchId, data.bank.batches.map { batch ->
                        batch.id to "${data.bank.accounts.firstOrNull { it.id == batch.accountId }?.name.orEmpty()} · ${batch.fileName}"
                    }, !busy, model::selectBatch)
                    if (batchId.isNotEmpty()) TextButton(onClick = model::editBatch, enabled = !busy) { Text(stringResource(R.string.bank_edit_configuration)) }
                    data.bank.batches.firstOrNull { it.id == batchId }?.takeIf { it.excludedLines.isNotEmpty() }?.let {
                        Text(stringResource(R.string.bank_excluded, it.excludedLines.joinToString()), color = MaterialTheme.colorScheme.error)
                    }
                    Text(stringResource(R.string.bank_counts, rows.count { it.resolution == BankResolution.PENDING }, rows.count { it.resolution in setOf(BankResolution.CREATED, BankResolution.LINKED) }))
                    BankChoice(stringResource(R.string.bank_filter), filter.toString(), listOf(
                        "0" to stringResource(R.string.bank_all), "1" to stringResource(R.string.bank_pending), "2" to stringResource(R.string.bank_without_receipt)
                    ), !busy, { filter = it.toInt() })
                    if (rows.any { it.resolution == BankResolution.PENDING }) {
                        OutlinedButton(onClick = { confirmCreate = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bank_create_new)) }
                    }
                }
                items(rows.filter { row -> when (filter) {
                    1 -> row.resolution == BankResolution.PENDING
                    2 -> row.documentUuid != null && data.movements.any { it.uuid == row.documentUuid && it.source == row.source && !it.hasDocument }
                    else -> true
                } }, key = { it.id }) { row ->
                    Surface(Modifier.fillMaxWidth().clickable(enabled = !busy) { selectedId = row.id }, shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            BankRowText(row)
                            Text(stringResource(bankStatus(row.resolution)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            if (row.documentUuid != null && data.movements.none { it.uuid == row.documentUuid && it.source == row.source }) Text(stringResource(R.string.bank_deleted), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
    if (newAccount) AlertDialog(onDismissRequest = { if (!busy) newAccount = false }, title = { Text(stringResource(R.string.bank_new_account)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.bank_account_hint))
            OutlinedTextField(accountName, { accountName = it }, label = { Text(stringResource(R.string.bank_alias)) }, enabled = !busy, singleLine = true)
            OutlinedTextField(currency, { currency = it }, label = { Text(stringResource(R.string.bank_currency)) }, enabled = !busy, singleLine = true)
            error?.let { BankError(it) }
        }
    }, confirmButton = { TextButton(onClick = { model.createAccount(accountName, currency) }, enabled = !busy && accountName.isNotBlank()) { Text(stringResource(R.string.bank_save)) } },
        dismissButton = { TextButton(onClick = { newAccount = false }, enabled = !busy) { Text(stringResource(R.string.bank_cancel)) } })
    if (confirmCreate) AlertDialog(onDismissRequest = { if (!busy) confirmCreate = false }, title = { Text(stringResource(R.string.bank_create_new)) },
        text = { Text(stringResource(R.string.bank_create_new_hint)) },
        confirmButton = { TextButton(onClick = { confirmCreate = false; model.createNew() }, enabled = !busy) { Text(stringResource(R.string.bank_continue)) } },
        dismissButton = { TextButton(onClick = { confirmCreate = false }, enabled = !busy) { Text(stringResource(R.string.bank_cancel)) } })
    rectification?.let { changes ->
        AlertDialog(onDismissRequest = model::dismissRectification, title = { Text(stringResource(R.string.bank_review_rectification)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.bank_rectification_summary, changes.changed, changes.protected))
                Text(stringResource(R.string.bank_rectification_protected))
                if (changes.reversibleCreatedIds.isNotEmpty()) Text(stringResource(R.string.bank_rectification_reversals, changes.reversibleCreatedIds.size))
                Text(stringResource(R.string.bank_rectification_hint))
                BankOperationStatus(operation, model::cancelOperation)
                error?.let { BankError(it) }
            }
        }, confirmButton = { TextButton(onClick = { model.rectify(excluded) }, enabled = !busy) { Text(stringResource(R.string.bank_apply_rectification)) } },
            dismissButton = { TextButton(onClick = model::dismissRectification, enabled = !busy) { Text(stringResource(R.string.bank_cancel)) } })
    }
    selected?.let { row ->
        ModalBottomSheet(onDismissRequest = { if (!busy) selectedId = null }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BankRowText(row)
                BankOperationStatus(operation, model::cancelOperation)
                error?.let { BankError(it) }
                if (row.resolution == BankResolution.PENDING) {
                    Text(stringResource(R.string.bank_match_hint))
                    val candidates = BankMatching.candidates(row, data.movements)
                    candidates.forEach { match ->
                        val used = data.bank.transactions.any { it.documentUuid == match.uuid && it.source == match.source }
                        OutlinedButton(onClick = { model.link(row, match) }, enabled = !busy && !used, modifier = Modifier.fillMaxWidth()) {
                            Text("${stringResource(R.string.bank_link)}: ${match.description} · ${date(match.date)}" + if (used) " · ${stringResource(R.string.bank_linked)}" else "")
                        }
                    }
                    data.bank.transactions.filter { it.batchId != row.batchId && it.resolution != BankResolution.DUPLICATE && BankMatching.sameEntry(row, it) }.take(20).forEach { other ->
                        OutlinedButton(onClick = { model.duplicate(row, other) }, enabled = !busy) { Text(stringResource(R.string.bank_mark_duplicate, other.description, date(other.date))) }
                    }
                    Text(stringResource(R.string.bank_special_hint), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { model.create(row) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (row.isExpense) R.string.bank_create_expense else R.string.bank_create_income)) }
                    TextButton(onClick = { model.classify(row, BankResolution.TRANSFER) }, enabled = !busy) { Text(stringResource(R.string.bank_transfer)) }
                    TextButton(onClick = { model.classify(row, BankResolution.IGNORED) }, enabled = !busy) { Text(stringResource(R.string.bank_ignore)) }
                } else {
                    Text(stringResource(bankStatus(row.resolution)))
                    val movementExists = data.movements.any { it.uuid == row.documentUuid && it.source == row.source }
                    row.documentUuid?.let { uuid ->
                        if (movementExists && row.source != null) TextButton(onClick = { selectedId = null; onOpen(requireNotNull(row.source), uuid) }, enabled = !busy) { Text(stringResource(R.string.bank_open_movement)) }
                        else Text(stringResource(R.string.bank_deleted))
                    }
                    when {
                        row.resolution == BankResolution.CREATED && movementExists -> TextButton(onClick = { confirmUndoId = row.id }, enabled = !busy) { Text(stringResource(R.string.bank_undo_creation)) }
                        row.resolution == BankResolution.CREATED -> TextButton(onClick = { confirmReopenId = row.id }, enabled = !busy) { Text(stringResource(R.string.bank_compare_again)) }
                        else -> TextButton(onClick = { model.reopen(row) }, enabled = !busy) { Text(stringResource(if (row.resolution == BankResolution.REVERSED) R.string.bank_compare_again else R.string.bank_reopen)) }
                    }
                }
            }
        }
    }
    data.bank.transactions.firstOrNull { it.id == confirmUndoId }?.let { row ->
        AlertDialog(onDismissRequest = { if (!busy) confirmUndoId = null }, title = { Text(stringResource(R.string.bank_undo_creation)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { BankRowText(row); Text(stringResource(R.string.bank_undo_creation_hint)) } },
            confirmButton = { TextButton(onClick = { confirmUndoId = null; model.undo(row) }, enabled = !busy) { Text(stringResource(R.string.bank_undo_creation)) } },
            dismissButton = { TextButton(onClick = { confirmUndoId = null }, enabled = !busy) { Text(stringResource(R.string.bank_cancel)) } })
    }
    data.bank.transactions.firstOrNull { it.id == confirmReopenId }?.let { row ->
        AlertDialog(onDismissRequest = { if (!busy) confirmReopenId = null }, title = { Text(stringResource(R.string.bank_compare_again)) },
            text = { Text(stringResource(R.string.bank_reopen_deleted_hint)) },
            confirmButton = { TextButton(onClick = { confirmReopenId = null; model.reopen(row) }, enabled = !busy) { Text(stringResource(R.string.bank_compare_again)) } },
            dismissButton = { TextButton(onClick = { confirmReopenId = null }, enabled = !busy) { Text(stringResource(R.string.bank_cancel)) } })
    }
}

@Composable
private fun BankColumns(table: BankCsvTable, mapping: BankMapping, enabled: Boolean, update: (BankMapping) -> Unit) {
    val options = listOf("-1" to stringResource(R.string.bank_not_used)) + table.headers.mapIndexed { index, name -> index.toString() to "${index + 1}. $name" }
    @Composable fun column(label: Int, selected: Int, change: (Int) -> Unit) {
        BankChoice(stringResource(label), selected.toString(), options, enabled, { change(it.toInt()) })
    }
    column(R.string.bank_date, mapping.date) { update(mapping.copy(date = it)) }
    column(R.string.bank_description, mapping.description) { update(mapping.copy(description = it)) }
    column(R.string.bank_amount, mapping.amount) { update(mapping.copy(amount = it)) }
    if (mapping.amount < 0) {
        column(R.string.bank_debit, mapping.debit) { update(mapping.copy(debit = it)) }
        column(R.string.bank_credit, mapping.credit) { update(mapping.copy(credit = it)) }
    }
    BankChoice(stringResource(R.string.bank_date_format), mapping.datePattern, BankCsv.datePatterns.map { it to it }, enabled, { update(mapping.copy(datePattern = it)) })
    BankChoice(stringResource(R.string.bank_decimal), mapping.decimalComma.toString(), listOf("true" to "1.234,56", "false" to "1,234.56"), enabled, { update(mapping.copy(decimalComma = it.toBoolean())) })
    if (mapping.amount >= 0) BankCheck(stringResource(R.string.bank_reverse), mapping.reverseSign, enabled) { update(mapping.copy(reverseSign = it)) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.bank_optional)) }
    if (advanced) {
        column(R.string.bank_currency, mapping.currency) { update(mapping.copy(currency = it)) }
        column(R.string.bank_reference, mapping.reference) { update(mapping.copy(reference = it, referenceIsDocumentNumber = it >= 0 && mapping.referenceIsDocumentNumber)) }
        if (mapping.reference >= 0) {
            BankCheck(stringResource(R.string.bank_reference_is_document), mapping.referenceIsDocumentNumber, enabled) { update(mapping.copy(referenceIsDocumentNumber = it)) }
            Text(stringResource(R.string.bank_reference_is_document_hint), style = MaterialTheme.typography.bodySmall)
        }
        column(R.string.bank_unique_id, mapping.transactionId) { update(mapping.copy(transactionId = it)) }
        Text(stringResource(R.string.bank_unique_id_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BankFileFormat(table: BankCsvTable, delimiter: String, headerLine: Int, enabled: Boolean, apply: (String, Int) -> Unit) {
    var expanded by rememberSaveable(table.hash) { mutableStateOf(false) }
    var selectedDelimiter by rememberSaveable(table.hash, delimiter) { mutableStateOf(delimiter) }
    var header by rememberSaveable(table.hash, headerLine) { mutableStateOf((headerLine + 1).toString()) }
    val headerNumber = header.toIntOrNull()?.takeIf { it >= 1 }
    TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.bank_file_format)) }
    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BankChoice(stringResource(R.string.bank_delimiter), selectedDelimiter, listOf(
                "AUTO" to stringResource(R.string.bank_automatic),
                ";" to stringResource(R.string.bank_semicolon),
                "," to stringResource(R.string.bank_comma),
                "\t" to stringResource(R.string.bank_tab)
            ), enabled) { selectedDelimiter = it }
            OutlinedTextField(
                value = header, onValueChange = { header = it }, enabled = enabled,
                label = { Text(stringResource(R.string.bank_header_row)) },
                supportingText = { Text(stringResource(R.string.bank_header_row_hint)) },
                isError = header.isNotEmpty() && headerNumber == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(onClick = { headerNumber?.let { apply(selectedDelimiter, it - 1) } },
                enabled = enabled && headerNumber != null, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bank_apply_format))
            }
            Text(stringResource(R.string.bank_format_mapping_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BankCheck(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp))
    }
}

@Composable
private fun BankOperationStatus(operation: BankOperation, cancel: () -> Unit) {
    val progress = when (operation) {
        is BankOperation.Running -> operation.progress
        is BankOperation.Completed -> operation.progress
        is BankOperation.Cancelled -> operation.progress
        else -> null
    }
    if (operation !is BankOperation.Running && operation !is BankOperation.Cancelled && progress == null) return
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (operation is BankOperation.Running) {
            if (progress != null && progress.total > 0) {
                LinearProgressIndicator(progress = { (progress.completed.toFloat() / progress.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            } else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (progress != null) {
            Text(stringResource(R.string.bank_operation_progress, progress.completed, progress.total), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.bank_operation_confirmed, progress.created, progress.linked), style = MaterialTheme.typography.bodySmall)
        }
        if (operation is BankOperation.Cancelled) Text(stringResource(R.string.bank_operation_cancelled), style = MaterialTheme.typography.bodySmall)
        if (operation is BankOperation.Running) TextButton(onClick = cancel) { Text(stringResource(R.string.bank_cancel_operation)) }
    }
}

@Composable
private fun BankChoice(label: String, selected: String, choices: List<Pair<String, String>>, enabled: Boolean, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(choices.firstOrNull { it.first == selected }?.second ?: stringResource(R.string.bank_select)) }
            DropdownMenu(expanded && enabled, { expanded = false }) { choices.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, enabled = enabled, onClick = { expanded = false; select(id) }) } }
        }
    }
}

@Composable
private fun BankRowText(row: BankTransaction) {
    val locale = LocalLocale.current.platformLocale
    val amount = remember(row.amount, row.currency, locale) {
        row.amount.toBigDecimalOrNull()?.let { number ->
            NumberFormat.getNumberInstance(locale).apply {
                val digits = runCatching { java.util.Currency.getInstance(row.currency).defaultFractionDigits }.getOrDefault(2).coerceAtLeast(0)
                minimumFractionDigits = digits
                maximumFractionDigits = maxOf(digits, number.scale().coerceIn(0, 6))
            }.format(number)
        }
    }
    Text(row.description, style = MaterialTheme.typography.titleSmall)
    Text("${date(row.date)} · ${amount ?: stringResource(R.string.bank_amount_unavailable)} ${row.currency}")
    if (row.reference.isNotBlank()) Text(row.reference, style = MaterialTheme.typography.bodySmall)
}
@Composable
private fun date(value: Long): String {
    val locale = LocalLocale.current.platformLocale
    return remember(value, locale) { DateFormat.getDateInstance(DateFormat.SHORT, locale).format(java.util.Date(value)) }
}
private fun bankStatus(status: BankResolution): Int = when (status) {
    BankResolution.PENDING -> R.string.bank_pending
    BankResolution.LINKED -> R.string.bank_linked
    BankResolution.CREATED -> R.string.bank_created
    BankResolution.TRANSFER -> R.string.bank_transfer_status
    BankResolution.IGNORED -> R.string.bank_ignored
    BankResolution.DUPLICATE -> R.string.bank_duplicate
    BankResolution.REVERSED -> R.string.bank_reversed
}
@Composable
private fun BankError(error: String) {
    Text(stringResource(when (error) {
        "BANK_DATE" -> R.string.bank_error_date
        "BANK_AMOUNT" -> R.string.bank_error_amount
        "BANK_MAPPING", "BANK_COLUMNS", "BANK_DESCRIPTION" -> R.string.bank_error_mapping
        "BANK_SIZE" -> R.string.bank_error_size
        "BANK_CURRENCY" -> R.string.bank_error_currency
        "BANK_ACCOUNT", "BANK_ACCOUNT_EXISTS" -> R.string.bank_error_account
        "BANK_INVALID_ROWS" -> R.string.bank_error_rows
        "BANK_ID_CONFLICT" -> R.string.bank_error_id
        "BANK_MAPPING_CHANGED" -> R.string.bank_error_mapping_changed
        "BANK_SOURCE_MISSING" -> R.string.bank_error_source_missing
        "BANK_MOVEMENT_CHANGED", "BANK_ALREADY_LINKED", "BANK_REVIEW" -> R.string.bank_error_changed
        "BANK_CSV" -> R.string.bank_error_csv
        else -> R.string.bank_error_storage
    }), color = MaterialTheme.colorScheme.error)
}
