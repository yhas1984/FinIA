package com.gastos.feature.invoices

import com.gastos.common.LocalizedNumbers
import com.gastos.common.SaveState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.AnimatedVisibility
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
import com.gastos.extension.fromDatePickerUtcMillis
import com.gastos.extension.toDatePickerUtcMillis
import com.gastos.feature.invoices.R
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditInvoiceScreen(
    invoiceId: Long,
    onNavigateBack: () -> Unit,
    viewModel: EditInvoiceViewModel = hiltViewModel()
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

    LaunchedEffect(invoiceId) {
        if (invoiceId > 0) {
            viewModel.loadInvoice(invoiceId, locale)
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
        topBar = { EssentialHeader(stringResource(if (invoiceId > 0) R.string.edit_invoice else R.string.new_invoice), { requestBack() }) },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (uiState.fieldErrors.isEmpty()) uiState.saveResult?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { viewModel.saveInvoice(locale) }, enabled = uiState.defaultsReady && !uiState.isLoading && !uiState.isSaving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .semantics { contentDescription = saveDescription }) {
                    if (uiState.isSaving) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)) }
                    Text(stringResource(if (uiState.isSaving) com.gastos.common.R.string.manual_saving else com.gastos.common.R.string.manual_save_expense))
                }
                }
            }
        }
    ) { padding ->
        if (!uiState.defaultsReady || uiState.isLoading) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(EssentialLayout.fieldSpacing)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(if (invoiceId > 0) com.gastos.common.R.string.manual_loading_document else com.gastos.common.R.string.manual_loading_preferences))
                uiState.error?.let { Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { if (invoiceId > 0) viewModel.loadInvoice(invoiceId, locale) else viewModel.prepareDefaults() }) { Text(stringResource(com.gastos.common.R.string.manual_retry_preferences)) }
                }
            }
        } else Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scrollState).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(EssentialLayout.fieldSpacing)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ManualTextField(form.total, viewModel::updateTotal, stringResource(R.string.total),
                    ManualField.AMOUNT, uiState.fieldErrors, uiState.validationAttempt, numeric = true, modifier = Modifier.weight(1f))
                ManualErrorTarget(ManualField.CURRENCY, uiState.fieldErrors, uiState.validationAttempt, Modifier.width(116.dp)) { target, error ->
                    ManualChoice(form.moneda, stringResource(R.string.currency), com.gastos.domain.model.SUPPORTED_CURRENCIES,
                        viewModel::updateMoneda, target.fillMaxWidth(), error)
                }
            }
            ManualTextField(form.proveedor, viewModel::updateProveedor, stringResource(R.string.provider),
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
                             form.isCustomSubcategory -> form.subcategoria.ifBlank { TransactionCategories.currentCustomOptionLabel(locale.language) }
                             form.subcategoria.isBlank() -> TransactionCategories.currentUncategorizedLabel(locale.language)
                            else -> form.subcategoria
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
                        uiState.availableSubcategories.forEach { subcategoria ->
                            DropdownMenuItem(
                        enabled = !uiState.isSaving,
                             text = { Text(TransactionCategories.displayCategory(subcategoria, locale.language)) },
                                onClick = {
                                    viewModel.selectSubcategory(value = subcategoria, isCustom = false)
                                    showSubcategoryPicker = false
                                }
                            )
                        }
                        DropdownMenuItem(
                        enabled = !uiState.isSaving,
                             text = { Text(TransactionCategories.currentCustomOptionLabel(locale.language)) },
                            onClick = {
                                viewModel.selectSubcategory(value = form.subcategoria, isCustom = true)
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

            ManualFormSection(stringResource(com.gastos.common.R.string.essential_document_data), ManualSection.DOCUMENT, uiState.fieldErrors, uiState.validationAttempt) {
                ManualTextField(form.numeroFactura, viewModel::updateNumeroFactura, stringResource(R.string.invoice_number))
                ManualTextField(form.nifEmisor, viewModel::updateNifEmisor, stringResource(R.string.issuer_tax_id))
                ManualTextField(form.nifReceptor, viewModel::updateNifReceptor, stringResource(R.string.receiver_tax_id))
                ManualChoice(form.paisCodigo, stringResource(com.gastos.common.R.string.manual_country), com.gastos.domain.model.SUPPORTED_FISCAL_COUNTRIES, viewModel::updatePaisCodigo)
            }
            ManualFormSection(stringResource(com.gastos.common.R.string.essential_taxes), ManualSection.TAXES, uiState.fieldErrors, uiState.validationAttempt,
                summary = manualTaxSummary(form.taxes, form.ivaPercent, locale)) {
                if (form.taxes.isEmpty()) ManualTextField(form.ivaPercent, viewModel::updateIvaPercent, stringResource(R.string.vat_percent),
                    ManualField.VAT, uiState.fieldErrors, uiState.validationAttempt, true)
                if (form.taxes.none { it.effect == com.gastos.domain.model.TaxEffect.WITHHOLDING }) ManualTextField(form.irpfPercent, viewModel::updateIrpfPercent, stringResource(R.string.irpf_percent),
                    ManualField.WITHHOLDING, uiState.fieldErrors, uiState.validationAttempt, true)
                ManualTextField(form.baseImponible, viewModel::updateBaseImponible, stringResource(R.string.tax_base), ManualField.BASE, uiState.fieldErrors, uiState.validationAttempt, true)
                ManualTextField(form.cuotaIva, viewModel::updateCuotaIva, stringResource(if (form.taxes.isEmpty()) R.string.vat_amount else com.gastos.common.R.string.taxes_total_label), ManualField.TAX_AMOUNT, uiState.fieldErrors, uiState.validationAttempt, true)
                ManualErrorTarget(ManualField.TAX_BREAKDOWN, uiState.fieldErrors, uiState.validationAttempt) { target, error ->
                    Column(target) {
                        com.gastos.common.TaxBreakdownEditor(form.taxes, form.moneda, locale, viewModel::updateTaxes, { viewModel.addTax(locale) }, showDisclosure = false)
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
                form.recalcFiscal(locale)?.let { fiscal ->
                    fiscal.baseImponible?.let { Text(stringResource(R.string.base_vat_irpf_breakdown, LocalizedNumbers.format(it, locale), form.moneda), style = MaterialTheme.typography.bodySmall) }
                    fiscal.ivaAmount?.let { Text(stringResource(com.gastos.common.R.string.taxes_total, LocalizedNumbers.format(it, locale), form.moneda), style = MaterialTheme.typography.bodySmall) }
                    if (fiscal.irpfAmount > 0) Text(stringResource(R.string.irpf_breakdown, LocalizedNumbers.format(fiscal.irpfAmount, locale), form.moneda), style = MaterialTheme.typography.bodySmall)
                }
            }
            ManualFormSection(stringResource(com.gastos.common.R.string.essential_notes), ManualSection.NOTES, uiState.fieldErrors, uiState.validationAttempt) {
                OutlinedTextField(form.notas, viewModel::updateNotas, shape = EssentialLayout.fieldShape, colors = essentialFieldColors(), enabled = !uiState.isSaving, label = { Text(stringResource(R.string.notes_optional)) }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            }
            if (uiState.products.isNotEmpty()) EssentialSection(stringResource(com.gastos.common.R.string.essential_products)) {
                uiState.products.forEach { product ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(product.descripcion, Modifier.weight(1f))
                        Text(com.gastos.domain.model.formatMoney(product.subtotal, form.moneda, locale))
                    }
                    com.gastos.common.TaxBreakdownSummary(product.taxes, form.moneda)
                }
            }
            uiState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(16.dp))
        }
    }

    }

    // Date picker dialog
    if (showDatePicker) {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = form.fecha
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)

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
