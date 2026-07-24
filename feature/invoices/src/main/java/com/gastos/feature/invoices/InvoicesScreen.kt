package com.gastos.feature.invoices

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvoicesScreen(
    onNavigateToEdit: (Long) -> Unit = {},
    viewModel: InvoicesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showFilterMenu by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }
    
    val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.forLanguageTag("es-ES"))

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Facturas") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(onClick = { showFilterMenu = !showFilterMenu }) {
                        Icon(Icons.Default.FilterList, contentDescription = "Filtrar")
                    }
                    DropdownMenu(
                        expanded = showFilterMenu,
                        onDismissRequest = { showFilterMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Todas") },
                            onClick = {
                                viewModel.filterByType(null)
                                showFilterMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Solo Gastos") },
                            onClick = {
                                viewModel.filterByType(InvoiceType.GASTO)
                                showFilterMenu = false
                            }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onNavigateToEdit(0L) }) {
                Icon(Icons.Default.Add, contentDescription = "Nueva factura")
            }
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (uiState.invoices.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No hay facturas",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Agrega tu primera factura con el botón +",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // Resumen total (convertido a la moneda por defecto del usuario).
            val total = uiState.totalGastosConvertido
            val target = uiState.defaultCurrency

            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Total Gastos",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (total != null) {
                                com.gastos.domain.model.formatMoney(total, target)
                            } else {
                                "— ($target)"
                            },
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = uiState.invoices,
                        key = { it.id }
                    ) { invoice ->
                        InvoiceCard(
                            invoice = invoice,
                            dateFormat = dateFormat,
                            onDelete = { viewModel.deleteInvoice(invoice) },
                            onEdit = { onNavigateToEdit(invoice.id) },
                            onRetryDrive = { viewModel.retryDriveUpload(invoice) },
                            onOpenDrive = {
                                invoice.driveWebViewLink?.let { link ->
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                                }
                            },
                            isPremium = uiState.isPremium,
                            isUploadingToDrive = invoice.id in uiState.uploadingToDrive
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InvoiceCard(
    invoice: Invoice,
    
    dateFormat: SimpleDateFormat,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onRetryDrive: () -> Unit,
    onOpenDrive: () -> Unit,
    isPremium: Boolean,
    isUploadingToDrive: Boolean
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = invoice.proveedor,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = dateFormat.format(Date(invoice.fecha)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = com.gastos.domain.model.formatMoney(invoice.total, invoice.moneda),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = if (invoice.tipo == InvoiceType.GASTO)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        text = if (invoice.tipo == InvoiceType.GASTO) "Gasto" else "Ingreso",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (invoice.tipo == InvoiceType.GASTO)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            if (invoice.nifEmisor != null || invoice.nifReceptor != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    invoice.nifEmisor?.let {
                        Text(
                            text = "NIF Emisor: $it",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    invoice.nifReceptor?.let {
                        Text(
                            text = "NIF Receptor: $it",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (invoice.imagenUri != null) {
                Spacer(modifier = Modifier.height(8.dp))
                when {
                    invoice.driveWebViewLink != null -> TextButton(onClick = onOpenDrive) {
                        Icon(Icons.Default.CloudDone, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Abrir foto en Drive")
                    }
                    invoice.driveUploadPending -> OutlinedButton(
                        onClick = onRetryDrive,
                        enabled = isPremium && !isUploadingToDrive
                    ) {
                        if (isUploadingToDrive) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.CloudUpload, contentDescription = null)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            when {
                                isUploadingToDrive -> "Subiendo..."
                                !isPremium -> "Drive requiere Premium"
                                else -> "Reintentar Drive"
                            }
                        )
                    }
                    else -> Text(
                        text = "Foto guardada localmente",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Editar",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = { showDeleteDialog = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Eliminar",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Eliminar factura") },
            text = { Text("¿Estás seguro de eliminar esta factura?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    }
                ) {
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}
