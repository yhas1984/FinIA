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
    var showDatePicker by remember { mutableStateOf(false) }
    var showCurrencyPicker by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }
    var showSubcategoryPicker by remember { mutableStateOf(false) }
    var showDocumentFields by remember { mutableStateOf(false) }

    LaunchedEffect(incomeId) {
        if (incomeId != 0L) {
            viewModel.loadIncome(incomeId, locale)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (incomeId != 0L) R.string.edit_income else R.string.new_income)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.saveIncome(locale) },
                        enabled = !uiState.isSaving &&
                            LocalizedNumbers.parse(form.monto, locale)?.let { it.isFinite() && it > 0 } == true &&
                            form.concepto.isNotBlank()
                    ) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.save))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Concepto
            OutlinedTextField(
                value = form.concepto,
                onValueChange = { viewModel.updateConcepto(it) },
                label = { Text(stringResource(R.string.concept)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Fecha
            OutlinedTextField(
                value = SimpleDateFormat("dd/MM/yyyy", locale).format(Date(form.fecha)),
                onValueChange = {},
                label = { Text(stringResource(R.string.date)) },
                modifier = Modifier.fillMaxWidth(),
                readOnly = true,
                trailingIcon = {
                    IconButton(onClick = { showDatePicker = true }) {
                        Icon(Icons.Default.CalendarToday, contentDescription = stringResource(R.string.select_date))
                    }
                }
            )

            // Moneda
            ExposedDropdownMenuBox(
                expanded = showCurrencyPicker,
                onExpandedChange = { showCurrencyPicker = it }
            ) {
                OutlinedTextField(
                    value = form.moneda,
                    onValueChange = {},
                    label = { Text(stringResource(R.string.currency)) },
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showCurrencyPicker) },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = showCurrencyPicker,
                    onDismissRequest = { showCurrencyPicker = false }
                ) {
                    com.gastos.domain.model.SUPPORTED_CURRENCIES.forEach { currency ->
                        DropdownMenuItem(
                            text = { Text(currency) },
                            onClick = {
                                viewModel.updateMoneda(currency)
                                showCurrencyPicker = false
                            }
                        )
                    }
                }
            }

            // Monto (cantidad principal)
            OutlinedTextField(
                value = form.monto,
                onValueChange = { viewModel.updateMonto(it) },
                label = { Text(stringResource(R.string.amount)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                prefix = { Text(form.moneda) }
            )

            ExposedDropdownMenuBox(
                expanded = showCategoryPicker,
                onExpandedChange = { showCategoryPicker = it }
            ) {
                OutlinedTextField(
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
                         text = { Text(TransactionCategories.currentUncategorizedLabel(locale.language)) },
                        onClick = {
                            viewModel.selectCategory(value = null, isCustomCategory = false)
                            showCategoryPicker = false
                        }
                    )
                    uiState.availableCategories.forEach { category ->
                        DropdownMenuItem(
                             text = { Text(TransactionCategories.displayCategory(category, locale.language)) },
                            onClick = {
                                viewModel.selectCategory(value = category, isCustomCategory = false)
                                showCategoryPicker = false
                            }
                        )
                    }
                    DropdownMenuItem(
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
                    onExpandedChange = { showSubcategoryPicker = it }
                ) {
                    OutlinedTextField(
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
                             text = { Text(TransactionCategories.currentUncategorizedLabel(locale.language)) },
                            onClick = {
                                viewModel.selectSubcategory(value = null, isCustom = false)
                                showSubcategoryPicker = false
                            }
                        )
                        uiState.availableSubcategories.forEach { subcategory ->
                            DropdownMenuItem(
                                 text = { Text(TransactionCategories.displayCategory(subcategory, locale.language)) },
                                onClick = {
                                    viewModel.selectSubcategory(value = subcategory, isCustom = false)
                                    showSubcategoryPicker = false
                                }
                            )
                        }
                        DropdownMenuItem(
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
                        value = form.subcategoria,
                        onValueChange = { viewModel.updateSubcategoria(it) },
                        label = { Text(stringResource(R.string.custom_subcategory)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text(stringResource(R.string.leave_empty_no_subcategory)) }
                    )
                }
            }

            TextButton(onClick = { showDocumentFields = !showDocumentFields }) { Text(stringResource(R.string.document_identity)) }
            if (showDocumentFields) {
                Row {
                    FilterChip(selected = form.documentKind == "factura_emitida", onClick = { viewModel.updateDocumentField("kind", "factura_emitida") }, label = { Text(stringResource(R.string.document_invoice)) })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = form.documentKind == "nomina", onClick = { viewModel.updateDocumentField("kind", "nomina") }, label = { Text(stringResource(R.string.document_payroll)) })
                }
                listOf(
                    Triple("number", form.documentNumber, R.string.document_number),
                    Triple("issuerTaxId", form.issuerTaxId, R.string.document_issuer_tax),
                    Triple("workerId", form.workerId, R.string.document_worker),
                    Triple("payPeriod", form.payPeriod, R.string.document_period),
                    Triple("paymentKind", form.paymentKind, R.string.document_payment_kind),
                    Triple("payrollReference", form.payrollReference, R.string.document_payroll_reference)
                ).forEach { (field, value, label) ->
                    OutlinedTextField(value, onValueChange = { viewModel.updateDocumentField(field, it) },
                        label = { Text(stringResource(label)) }, modifier = Modifier.fillMaxWidth())
                }
            }

            // Devengado (bruto) y Líquido (neto)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = form.totalDevengado,
                    onValueChange = { viewModel.updateTotalDevengado(it) },
                    label = { Text(stringResource(R.string.gross_amount)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    prefix = { Text(form.moneda) }
                )
                OutlinedTextField(
                    value = form.totalNeto,
                    onValueChange = { viewModel.updateTotalNeto(it) },
                    label = { Text(stringResource(R.string.net_amount)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    prefix = { Text(form.moneda) }
                )
            }

            // Cálculo automático: si hay devengado e IRPF, mostrar neto
            val dev = LocalizedNumbers.parse(form.totalDevengado, locale) ?: 0.0
            val irpf = LocalizedNumbers.parse(form.irpfPercent, locale) ?: 0.0
            if (dev > 0 && irpf > 0 && form.documentKind != "nomina") {
                val netoCalc = dev * (1.0 - irpf / 100.0)
                Text(
                    stringResource(R.string.calculated_net, String.format("%.2f", netoCalc), form.moneda),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            // Fuente
            OutlinedTextField(
                value = form.fuente,
                onValueChange = { viewModel.updateFuente(it) },
                label = { Text(stringResource(R.string.source_optional)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // IVA e IRPF
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (form.taxes.isEmpty()) OutlinedTextField(
                    value = form.ivaPercent,
                    onValueChange = { viewModel.updateIvaPercent(it) },
                    label = { Text(stringResource(R.string.vat_percent)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                if (form.taxes.none { it.effect == com.gastos.domain.model.TaxEffect.WITHHOLDING }) OutlinedTextField(
                    value = form.irpfPercent,
                    onValueChange = { viewModel.updateIrpfPercent(it) },
                    label = { Text(stringResource(R.string.irpf_percent)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }

            com.gastos.common.TaxBreakdownEditor(form.taxes, form.moneda, locale, viewModel::updateTaxes, { viewModel.addTax(locale) })
            if (form.taxes.isNotEmpty()) OutlinedTextField(form.taxBase, viewModel::updateTaxBase,
                label = { Text(stringResource(com.gastos.common.R.string.taxes_base)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())

            // Notas
            OutlinedTextField(
                value = form.notas,
                onValueChange = { viewModel.updateNotas(it) },
                label = { Text(stringResource(R.string.notes_optional)) },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                minLines = 3
            )

            // Resultado del guardado
            uiState.saveResult?.let { msg ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (uiState.saveState is SaveState.Error)
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (uiState.saveState is SaveState.Error)
                            MaterialTheme.colorScheme.onErrorContainer
                        else
                            MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Date picker dialog
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
