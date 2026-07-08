package com.gastos.feature.invoices

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.MenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gastos.domain.model.InvoiceType
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditInvoiceScreen(
    invoiceId: Long,
    onNavigateBack: () -> Unit,
    viewModel: EditInvoiceViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val form by viewModel.form.collectAsState()
    val scrollState = rememberScrollState()
    var showDatePicker by remember { mutableStateOf(false) }
    var showCurrencyPicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = form.fecha
    )

    // Cuando el VM carga una factura existente, form.fecha cambia; refrescamos
    // el estado del DatePicker para que abra en la fecha real (no en "hoy").
    LaunchedEffect(form.fecha) {
        datePickerState.selectedDateMillis = form.fecha
    }

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
            viewModel.clearSaveResult()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (invoiceId > 0) "Editar Factura" else "Nueva Factura") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.saveInvoice() },
                        enabled = !uiState.isSaving
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Guardar")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Tipo de factura
            Text("Tipo", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = form.tipo == InvoiceType.GASTO,
                    onClick = { viewModel.updateTipo(InvoiceType.GASTO) },
                    label = { Text("Gasto") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = form.tipo == InvoiceType.INGRESO,
                    onClick = { viewModel.updateTipo(InvoiceType.INGRESO) },
                    label = { Text("Ingreso") },
                    modifier = Modifier.weight(1f)
                )
            }

            // Proveedor
            OutlinedTextField(
                value = form.proveedor,
                onValueChange = { viewModel.updateProveedor(it) },
                label = { Text("Proveedor") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Fecha
            OutlinedTextField(
                value = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(form.fecha)),
                onValueChange = {},
                label = { Text("Fecha") },
                modifier = Modifier.fillMaxWidth(),
                readOnly = true,
                trailingIcon = {
                    IconButton(onClick = { showDatePicker = true }) {
                        Icon(Icons.Default.CalendarToday, contentDescription = "Seleccionar fecha")
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
                    label = { Text("Moneda") },
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showCurrencyPicker) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth()
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

            // Total (input principal)
            OutlinedTextField(
                value = form.total,
                onValueChange = { viewModel.updateTotal(it) },
                label = { Text("Total (IVA incluido)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                prefix = { Text(form.moneda) },
                supportingText = {
                    Text("Lo que pagas en el mostrador.")
                }
            )

            // IVA % (input; con productos del OCR, se ignora: cada línea trae el suyo)
            OutlinedTextField(
                value = form.ivaPercent,
                onValueChange = { viewModel.updateIvaPercent(it) },
                label = { Text("IVA %") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                supportingText = {
                    Text("Sólo se usa para facturas sin productos asociados. En tickets OCR con productos al 4/10/21%, cada línea lleva su IVA real.")
                }
            )

            // IRPF % (input opcional)
            OutlinedTextField(
                value = form.irpfPercent,
                onValueChange = { viewModel.updateIrpfPercent(it) },
                label = { Text("IRPF % (opcional)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true
            )

            // Desglose fiscal calculado automáticamente (read-only)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = form.baseImponible,
                    onValueChange = {},
                    label = { Text("Base imponible") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    readOnly = true,
                    enabled = false,
                    prefix = { Text(form.moneda + " ") }
                )
                OutlinedTextField(
                    value = form.cuotaIva,
                    onValueChange = {},
                    label = { Text("Cuota IVA") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    readOnly = true,
                    enabled = false,
                    prefix = { Text(form.moneda + " ") }
                )
            }

            // IRPF cuota (read-only, sólo si IRPF > 0)
            if (form.cuotaIrpf.toDoubleOrNull()?.let { it > 0.0 } == true) {
                OutlinedTextField(
                    value = "-" + form.cuotaIrpf,
                    onValueChange = {},
                    label = { Text("Retención IRPF") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    readOnly = true,
                    enabled = false,
                    prefix = { Text(form.moneda + " ") },
                    supportingText = {
                        Text("El IRPF es una retención: se resta del total que recibes.")
                    }
                )
            }

            // NIF Emisor
            OutlinedTextField(
                value = form.nifEmisor,
                onValueChange = { viewModel.updateNifEmisor(it) },
                label = { Text("NIF/CIF Emisor (opcional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // NIF Receptor
            OutlinedTextField(
                value = form.nifReceptor,
                onValueChange = { viewModel.updateNifReceptor(it) },
                label = { Text("NIF/CIF Receptor (opcional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Notas
            OutlinedTextField(
                value = form.notas,
                onValueChange = { viewModel.updateNotas(it) },
                label = { Text("Notas (opcional)") },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                minLines = 3
            )

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
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { viewModel.updateFecha(it) }
                        showDatePicker = false
                    }
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancelar") }
            }
        ) {
            androidx.compose.material3.DatePicker(state = datePickerState)
        }
    }
}
