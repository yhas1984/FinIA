package com.gastos.feature.invoices

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
    var showDatePicker by remember { mutableStateOf(false) }
    var showCurrencyPicker by remember { mutableStateOf(false) }
    var showCountryPicker by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }
    var showSubcategoryPicker by remember { mutableStateOf(false) }

    LaunchedEffect(invoiceId) {
        if (invoiceId > 0) {
            viewModel.loadInvoice(invoiceId)
        }
    }

    LaunchedEffect(uiState.saveResult) {
        val result = uiState.saveResult
        if (result != null && !result.contains("Error")) {
            kotlinx.coroutines.delay(1000)
            onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (invoiceId > 0) stringResource(R.string.edit_invoice) else stringResource(R.string.new_invoice)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.saveInvoice() },
                        enabled = !uiState.isSaving &&
                            form.total.toDoubleOrNull()?.let { it.isFinite() && it > 0 } == true &&
                            form.proveedor.isNotBlank()
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
            // Proveedor
            OutlinedTextField(
                value = form.proveedor,
                onValueChange = { viewModel.updateProveedor(it) },
                label = { Text(stringResource(R.string.provider)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = form.numeroFactura,
                onValueChange = { viewModel.updateNumeroFactura(it) },
                label = { Text(stringResource(R.string.invoice_number)) },
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

            // Total
            OutlinedTextField(
                value = form.total,
                onValueChange = { viewModel.updateTotal(it) },
                label = { Text(stringResource(R.string.total)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                prefix = { Text(form.moneda) }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = form.baseImponible,
                    onValueChange = { viewModel.updateBaseImponible(it) },
                    label = { Text(stringResource(R.string.tax_base)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    prefix = { Text(form.moneda) }
                )
                OutlinedTextField(
                    value = form.cuotaIva,
                    onValueChange = { viewModel.updateCuotaIva(it) },
                    label = { Text(stringResource(R.string.vat_amount)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    prefix = { Text(form.moneda) }
                )
            }

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
                             text = { Text(TransactionCategories.currentUncategorizedLabel(locale.language)) },
                            onClick = {
                                viewModel.selectSubcategory(value = null, isCustom = false)
                                showSubcategoryPicker = false
                            }
                        )
                        uiState.availableSubcategories.forEach { subcategoria ->
                            DropdownMenuItem(
                             text = { Text(TransactionCategories.displayCategory(subcategoria, locale.language)) },
                                onClick = {
                                    viewModel.selectSubcategory(value = subcategoria, isCustom = false)
                                    showSubcategoryPicker = false
                                }
                            )
                        }
                        DropdownMenuItem(
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
                        value = form.subcategoria,
                        onValueChange = { viewModel.updateSubcategoria(it) },
                        label = { Text(stringResource(R.string.custom_subcategory)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text(stringResource(R.string.leave_empty_no_subcategory)) }
                    )
                }
            }

            // IVA e IRPF
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = form.ivaPercent,
                    onValueChange = { viewModel.updateIvaPercent(it) },
                    label = { Text(stringResource(R.string.vat_percent)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedTextField(
                    value = form.irpfPercent,
                    onValueChange = { viewModel.updateIrpfPercent(it) },
                    label = { Text(stringResource(R.string.irpf_percent)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }

            // Desglose fiscal en vivo
            form.recalcFiscal()?.let { fb ->
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.base_vat_irpf_breakdown, String.format("%.2f", fb.baseImponible), form.moneda), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.vat_breakdown, String.format("%.2f", fb.ivaAmount), form.moneda), style = MaterialTheme.typography.bodySmall)
                        if (fb.irpfAmount > 0) Text(stringResource(R.string.irpf_breakdown, String.format("%.2f", fb.irpfAmount), form.moneda), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Sección plegable: Más datos fiscales
            var showAdvanced by remember { mutableStateOf(false) }
            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Icon(if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.more_tax_data))
            }
            AnimatedVisibility(visible = showAdvanced) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = form.nifEmisor,
                        onValueChange = { viewModel.updateNifEmisor(it) },
                        label = { Text(stringResource(R.string.issuer_tax_id)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = form.nifReceptor,
                        onValueChange = { viewModel.updateNifReceptor(it) },
                        label = { Text(stringResource(R.string.receiver_tax_id)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = form.notas,
                        onValueChange = { viewModel.updateNotas(it) },
                        label = { Text(stringResource(R.string.notes_optional)) },
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        minLines = 3
                    )
                }
            }

            // Resultado del guardado
            uiState.saveResult?.let { msg ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (msg.contains("Error"))
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (msg.contains("Error"))
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
