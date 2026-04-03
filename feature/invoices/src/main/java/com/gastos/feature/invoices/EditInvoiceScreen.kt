package com.gastos.feature.invoices

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
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
    var showCountryPicker by remember { mutableStateOf(false) }

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
                title = { Text(if (invoiceId > 0) "Editar Factura" else "Nueva Factura") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
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
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = showCurrencyPicker,
                    onDismissRequest = { showCurrencyPicker = false }
                ) {
                    listOf("EUR", "USD", "MXN", "ARS", "COP", "CLP", "PEN").forEach { currency ->
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
                label = { Text("Total") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                prefix = { Text(form.moneda) }
            )

            // IVA e IRPF
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = form.ivaPercent,
                    onValueChange = { viewModel.updateIvaPercent(it) },
                    label = { Text("IVA %") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedTextField(
                    value = form.irpfPercent,
                    onValueChange = { viewModel.updateIrpfPercent(it) },
                    label = { Text("IRPF %") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
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
                initialSelectedDateMillis = form.fecha
            )
            androidx.compose.material3.DatePicker(
                state = datePickerState
            )
            Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancelar") }
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { viewModel.updateFecha(it) }
                        showDatePicker = false
                    }
                ) { Text("OK") }
            }
        }
    }
}
