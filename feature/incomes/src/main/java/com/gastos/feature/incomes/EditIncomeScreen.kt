package com.gastos.feature.incomes

import com.gastos.common.LocalizedNumbers
import com.gastos.common.SaveState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import com.gastos.common.design.EssentialSection
import com.gastos.common.design.*
import com.gastos.common.ManualField
import com.gastos.common.ManualSection
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.gastos.common.design.EssentialHeader
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gastos.domain.model.TransactionCategories
import com.gastos.feature.incomes.R
import com.gastos.extension.fromDatePickerUtcMillis
import com.gastos.extension.toDatePickerUtcMillis
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditIncomeScreen(
    incomeId: Long,
    onNavigateBack: () -> Unit,
    viewModel: EditIncomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val locale = LocalLocale.current.platformLocale
    val scrollState = rememberScrollState()
    var showDiscard by rememberSaveable { mutableStateOf(false) }
    fun requestBack() { if (!uiState.isSaving) { if (viewModel.hasChanges) showDiscard = true else onNavigateBack() } }
    BackHandler { requestBack() }
    if (showDiscard) AlertDialog(onDismissRequest = { showDiscard = false },
        title = { Text(stringResource(com.gastos.common.R.string.essential_discard_title)) },
        confirmButton = { TextButton(onClick = { showDiscard = false; onNavigateBack() }) { Text(stringResource(com.gastos.common.R.string.essential_discard)) } },
        dismissButton = { TextButton(onClick = { showDiscard = false }) { Text(stringResource(com.gastos.common.R.string.essential_keep_editing)) } })
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showCategoryPicker by rememberSaveable { mutableStateOf(false) }
    var showSubcategoryPicker by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(incomeId) {
        if (incomeId != 0L) {
            viewModel.loadIncome(incomeId, locale)
        } else viewModel.prepareDefaults()
    }

    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(uiState.isSaving) {
        if (uiState.isSaving) {
            showDatePicker = false; showCategoryPicker = false; showSubcategoryPicker = false
            keyboard?.hide(); focus.clearFocus()
        }
    }

    LaunchedEffect(uiState.saveState) {
        if (uiState.saveState == SaveState.Success) {
            viewModel.clearSaveResult()
            onNavigateBack()
        }
    }

    if (uiState.duplicates.isNotEmpty()) {
        AlertDialog(onDismissRequest = viewModel::dismissDuplicate,
            title = { Text(stringResource(R.string.document_duplicate)) },
            text = { Column {
                uiState.duplicates.forEach { match ->
                    val record = match.existing
                    Text("${record.issuer} · ${record.number.orEmpty()} · ${record.date} · ${record.amount} ${record.currency}")
                }
                Text(stringResource(R.string.document_duplicate_help))
            } },
            confirmButton = {
                if (uiState.duplicates.none { it.strength == com.gastos.domain.model.DuplicateStrength.STRONG }) {
                    TextButton(onClick = { viewModel.confirmDistinct(locale) }) { Text(stringResource(R.string.document_distinct)) }
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDuplicate) { Text(stringResource(R.string.document_correct)) } })
    }

    val saveDescription = stringResource(R.string.save)
    CompositionLocalProvider(LocalManualInputEnabled provides !uiState.isSaving) {
    Scaffold(
        topBar = { EssentialHeader(stringResource(if (incomeId != 0L) R.string.edit_income else R.string.new_income), { requestBack() }) },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (uiState.fieldErrors.isEmpty()) uiState.saveResult?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { viewModel.saveIncome(locale) }, enabled = uiState.defaultsReady && !uiState.isLoading && !uiState.isSaving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .semantics { contentDescription = saveDescription }) {
                    if (uiState.isSaving) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)) }
                    Text(stringResource(if (uiState.isSaving) com.gastos.common.R.string.manual_saving else com.gastos.common.R.string.manual_save_income))
                }
                }
            }
        }
    ) { padding ->
        if (!uiState.defaultsReady || uiState.isLoading) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(EssentialLayout.fieldSpacing)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(if (incomeId != 0L) com.gastos.common.R.string.manual_loading_document else com.gastos.common.R.string.manual_loading_preferences))
                uiState.error?.let { Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { if (incomeId != 0L) viewModel.loadIncome(incomeId, locale) else viewModel.prepareDefaults() }) { Text(stringResource(com.gastos.common.R.string.manual_retry_preferences)) }
                }
            }
        } else Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scrollState).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(EssentialLayout.fieldSpacing)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ManualTextField(form.monto, viewModel::updateMonto, stringResource(R.string.amount),
                    ManualField.AMOUNT, uiState.fieldErrors, uiState.validationAttempt, numeric = true, modifier = Modifier.weight(1f))
                ManualErrorTarget(ManualField.CURRENCY, uiState.fieldErrors, uiState.validationAttempt, Modifier.width(116.dp)) { target, error ->
                    ManualChoice(form.moneda, stringResource(R.string.currency), com.gastos.domain.model.SUPPORTED_CURRENCIES,
                        viewModel::updateMoneda, target.fillMaxWidth(), error)
                }
            }
            ManualTextField(form.concepto, viewModel::updateConcepto, stringResource(R.string.concept),
                ManualField.CONCEPT, uiState.fieldErrors, uiState.validationAttempt)
            // Fecha
            OutlinedTextField(
                    shape = EssentialLayout.fieldShape, colors = essentialFieldColors(),
                    enabled = !uiState.isSaving,
                value = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, locale).format(Date(form.fecha)),
                onValueChange = {},
                label = { Text(stringResource(R.string.date)) },
                modifier = Modifier.fillMaxWidth(),
                readOnly = true,
                trailingIcon = {
                    IconButton(onClick = { showDatePicker = true }, enabled = !uiState.isSaving) {
                        Icon(Icons.Default.CalendarToday, contentDescription = stringResource(R.string.select_date))
                    }
                }
            )

            ExposedDropdownMenuBox(
                expanded = showCategoryPicker,
                onExpandedChange = { if (!uiState.isSaving) showCategoryPicker = it }
            ) {
                OutlinedTextField(
                    shape = EssentialLayout.fieldShape, colors = essentialFieldColors(),
                    enabled = !uiState.isSaving,
                    value = when {
                        form.isCustomCategory && form.categoria.isNotBlank() -> form.categoria
                         form.isCustomCategory -> TransactionCategories.currentCustomOptionLabel(locale.language)
                         else -> TransactionCategories.displayCategory(form.categoria, locale.language)
                    },
                    onValueChange = {},
                    label = { Text(stringResource(R.string.category)) },
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showCategoryPicker) },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = showCategoryPicker,
                    onDismissRequest = { showCategoryPicker = false }
                ) {
                    DropdownMenuItem(
                        enabled = !uiState.isSaving,
                         text = { Text(TransactionCategories.currentUncategorizedLabel(locale.language)) },
                        onClick = {
                            viewModel.selectCategory(value = null, isCustomCategory = false)
                            showCategoryPicker = false
                        }
                    )
                    uiState.availableCategories.forEach { category ->
                        DropdownMenuItem(
                        enabled = !uiState.isSaving,
                             text = { Text(TransactionCategories.displayCategory(category, locale.language)) },
                            onClick = {
                                viewModel.selectCategory(value = category, isCustomCategory = false)
                                showCategoryPicker = false
                            }
                        )
                    }
                    DropdownMenuItem(
                        enabled = !uiState.isSaving,
                         text = { Text(TransactionCategories.currentCustomOptionLabel(locale.language)) },
                        onClick = {
                            viewModel.selectCategory(
                                value = if (form.isCustomCategory) form.categoria else null,
                                isCustomCategory = true
                            )
                            showCategoryPicker = false
                        }
                    )
                }
            }

                if (form.isCustomCategory) {
                    OutlinedTextField(
                    shape = EssentialLayout.fieldShape, colors = essentialFieldColors(),
                    enabled = !uiState.isSaving,
                        value = form.categoria,
                        onValueChange = { viewModel.updateCategoria(it) },
                        label = { Text(stringResource(R.string.custom_category)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text(stringResource(R.string.leave_empty_no_category)) }
                    )
                }

            if (form.categoria.isNotBlank()) {
                ExposedDropdownMenuBox(
                    expanded = showSubcategoryPicker,
                    onExpandedChange = { if (!uiState.isSaving) showSubcategoryPicker = it }
                ) {
                    OutlinedTextField(
                    shape = EssentialLayout.fieldShape, colors = essentialFieldColors(),
                    enabled = !uiState.isSaving,
                        value = when {
                            form.isCustomSubcategory && form.subcategoria.isNotBlank() -> form.subcategoria
                             form.isCustomSubcategory -> TransactionCategories.currentCustomOptionLabel(locale.language)
                             else -> TransactionCategories.displayCategory(form.subcategoria, locale.language)
                        },
                        onValueChange = {},
                        label = { Text(stringResource(R.string.subcategory)) },
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showSubcategoryPicker) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = showSubcategoryPicker,
                        onDismissRequest = { showSubcategoryPicker = false }
                    ) {
                        DropdownMenuItem(
                        enabled = !uiState.isSaving,
                             text = { Text(TransactionCategories.currentUncategorizedLabel(locale.language)) },
                            onClick = {
                                viewModel.selectSubcategory(value = null, isCustom = false)
                                showSubcategoryPicker = false
                            }
                        )
                        uiState.availableSubcategories.forEach { subcategory ->
                            DropdownMenuItem(
                        enabled = !uiState.isSaving,
                                 text = { Text(TransactionCategories.displayCategory(subcategory, locale.language)) },
                                onClick = {
                                    viewModel.selectSubcategory(value = subcategory, isCustom = false)
                                    showSubcategoryPicker = false
                                }
                            )
                        }
                        DropdownMenuItem(
                        enabled = !uiState.isSaving,
                         text = { Text(TransactionCategories.currentCustomOptionLabel(locale.language)) },
                            onClick = {
                                viewModel.selectSubcategory(
                                    value = if (form.isCustomSubcategory) form.subcategoria else null,
                                    isCustom = true
                                )
                                showSubcategoryPicker = false
                            }
                        )
                    }
                }

                if (form.isCustomSubcategory) {
                    OutlinedTextField(
                    shape = EssentialLayout.fieldShape, colors = essentialFieldColors(),
                    enabled = !uiState.isSaving,
                        value = form.subcategoria,
                        onValueChange = { viewModel.updateSubcategoria(it) },
                        label = { Text(stringResource(R.string.custom_subcategory)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text(stringResource(R.string.leave_empty_no_subcategory)) }
                    )
                }
            }

            Text(stringResource(com.gastos.common.R.string.manual_income_kind), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("" to com.gastos.common.R.string.manual_general_income, "factura_emitida" to R.string.document_invoice,
                    "nomina" to R.string.document_payroll).forEach { (value, label) ->
                    FilterChip(enabled = !uiState.isSaving, selected = if (value.isEmpty()) form.documentKind !in listOf("factura_emitida", "nomina") else form.documentKind == value, onClick = { viewModel.updateDocumentField("kind", value) }, label = { Text(stringResource(label)) })
                }
            }
            ManualFormSection(stringResource(com.gastos.common.R.string.essential_document_data), ManualSection.DOCUMENT, uiState.fieldErrors, uiState.validationAttempt) {
                ManualTextField(form.fuente, viewModel::updateFuente, stringResource(R.string.source_optional))
                listOf(Triple("number", form.documentNumber, R.string.document_number), Triple("issuerTaxId", form.issuerTaxId, R.string.document_issuer_tax),
                    Triple("paymentKind", form.paymentKind, R.string.document_payment_kind)).forEach { (field, value, label) ->
                    ManualTextField(value, { viewModel.updateDocumentField(field, it) }, stringResource(label))
                }
                ManualChoice(form.paisCodigo, stringResource(com.gastos.common.R.string.manual_country), com.gastos.domain.model.SUPPORTED_FISCAL_COUNTRIES, viewModel::updatePaisCodigo)
            }
            key(form.documentKind) {
                ManualFormSection(stringResource(com.gastos.common.R.string.essential_payroll), ManualSection.PAYROLL, uiState.fieldErrors, uiState.validationAttempt,
                    initiallyExpanded = form.documentKind == "nomina") {
                    ManualTextField(form.totalDevengado, viewModel::updateTotalDevengado, stringResource(R.string.gross_amount), ManualField.GROSS, uiState.fieldErrors, uiState.validationAttempt, true)
                    ManualTextField(form.totalNeto, viewModel::updateTotalNeto, stringResource(R.string.net_amount), ManualField.NET, uiState.fieldErrors, uiState.validationAttempt, true)
                    listOf(Triple("workerId", form.workerId, R.string.document_worker), Triple("payPeriod", form.payPeriod, R.string.document_period),
                        Triple("payrollReference", form.payrollReference, R.string.document_payroll_reference)).forEach { (field, value, label) ->
                        ManualTextField(value, { viewModel.updateDocumentField(field, it) }, stringResource(label))
                    }
                }
            }
            ManualFormSection(stringResource(com.gastos.common.R.string.essential_taxes), ManualSection.TAXES, uiState.fieldErrors, uiState.validationAttempt,
                summary = manualTaxSummary(form.taxes, form.ivaPercent, locale)) {
                if (form.taxes.isEmpty()) ManualTextField(form.ivaPercent, viewModel::updateIvaPercent, stringResource(R.string.vat_percent),
                    ManualField.VAT, uiState.fieldErrors, uiState.validationAttempt, true)
                if (form.taxes.none { it.effect == com.gastos.domain.model.TaxEffect.WITHHOLDING }) ManualTextField(form.irpfPercent, viewModel::updateIrpfPercent, stringResource(R.string.irpf_percent),
                    ManualField.WITHHOLDING, uiState.fieldErrors, uiState.validationAttempt, true)
                if (form.taxes.isNotEmpty()) ManualTextField(form.taxBase, viewModel::updateTaxBase, stringResource(com.gastos.common.R.string.taxes_base), ManualField.BASE, uiState.fieldErrors, uiState.validationAttempt, true)
                ManualErrorTarget(ManualField.TAX_BREAKDOWN, uiState.fieldErrors, uiState.validationAttempt) { target, error ->
                    Column(target) {
                        com.gastos.common.TaxBreakdownEditor(form.taxes, form.moneda, locale, viewModel::updateTaxes, { viewModel.addTax(locale) }, showDisclosure = false)
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            ManualFormSection(stringResource(com.gastos.common.R.string.essential_notes), ManualSection.NOTES, uiState.fieldErrors, uiState.validationAttempt) {
                OutlinedTextField(form.notas, viewModel::updateNotas, shape = EssentialLayout.fieldShape, colors = essentialFieldColors(), enabled = !uiState.isSaving, label = { Text(stringResource(R.string.notes_optional)) }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            }
            uiState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(16.dp))
        }
    }

    }

    if (showDatePicker) {
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = { /* handled by state */ }
        ) {
            val datePickerState = rememberDatePickerState(
                initialSelectedDateMillis = form.fecha.toDatePickerUtcMillis()
            )
            androidx.compose.material3.DatePicker(
                state = datePickerState
            )
            Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.cancel)) }
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            viewModel.updateFecha(it.fromDatePickerUtcMillis())
                        }
                        showDatePicker = false
                    }
                ) { Text(stringResource(R.string.ok)) }
            }
        }
    }
}
