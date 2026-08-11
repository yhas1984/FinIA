package com.gastos.feature.invoices

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.TransactionCategories
import com.gastos.feature.invoices.R
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

private const val UNCATEGORIZED_FILTER = "__uncategorized__"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvoicesScreen(
    onNavigateToEdit: (Long) -> Unit = {},
    viewModel: InvoicesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showFilterMenu by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val language = LocalLocale.current.platformLocale.language
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

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
                title = { Text(stringResource(R.string.invoices_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(onClick = { showFilterMenu = !showFilterMenu }) {
                        Icon(Icons.Default.FilterList, contentDescription = stringResource(R.string.filter))
                    }
                    DropdownMenu(
                        expanded = showFilterMenu,
                        onDismissRequest = { showFilterMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.all_items)) },
                            onClick = {
                                viewModel.filterByType(null)
                                showFilterMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.only_expenses)) },
                            onClick = {
                                viewModel.filterByType(InvoiceType.GASTO)
                                showFilterMenu = false
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (!uiState.hasAnyInvoices) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.no_invoices),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.add_first_invoice),
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
                            text = stringResource(R.string.total_expenses),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (total != null) {
                                com.gastos.domain.model.formatMoney(total, target)
                            } else {
                                stringResource(R.string.no_conversion_total, target)
                            },
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = uiState.selectedCategoryFilter == null,
                        onClick = { viewModel.filterByCategory(null) },
                        label = { Text(stringResource(R.string.all_items)) }
                    )
                    FilterChip(
                        selected = uiState.selectedCategoryFilter == UNCATEGORIZED_FILTER,
                        onClick = { viewModel.filterByCategory(UNCATEGORIZED_FILTER) },
                         label = { Text(TransactionCategories.currentUncategorizedLabel(language)) }
                    )
                    uiState.availableCategories.forEach { category ->
                        FilterChip(
                            selected = uiState.selectedCategoryFilter == category,
                            onClick = { viewModel.filterByCategory(category) },
                             label = { Text(TransactionCategories.displayCategory(category, language)) }
                        )
                    }
                }

                if (uiState.selectedCategoryFilter != null &&
                    uiState.selectedCategoryFilter != UNCATEGORIZED_FILTER &&
                    uiState.availableSubcategories.isNotEmpty()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = uiState.selectedSubcategoryFilter == null,
                            onClick = { viewModel.filterBySubcategory(null) },
                            label = { Text(stringResource(R.string.all_items)) }
                        )
                        uiState.availableSubcategories.forEach { subcategory ->
                            FilterChip(
                                selected = uiState.selectedSubcategoryFilter == subcategory,
                                onClick = { viewModel.filterBySubcategory(subcategory) },
                                 label = { Text(TransactionCategories.displayCategory(subcategory, language)) }
                            )
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (uiState.invoices.isEmpty()) {
                        item {
                            FilteredEmptyState(
                                category = uiState.selectedCategoryFilter,
                                language = language,
                                onClearFilter = { viewModel.filterByCategory(null) }
                            )
                        }
                    }
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
                                    openTrustedUrl(
                                        context = context,
                                        rawUrl = link,
                                        allowedHosts = setOf("drive.google.com", "docs.google.com"),
                                        onError = { message ->
                                            coroutineScope.launch { snackbarHostState.showSnackbar(message) }
                                        }
                                    )
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
private fun FilteredEmptyState(
    category: String?,
    language: String,
    onClearFilter: () -> Unit
) {
    val categoryLabel = if (category == UNCATEGORIZED_FILTER) {
        TransactionCategories.currentUncategorizedLabel(language)
    } else {
        category.orEmpty()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Default.FilterList,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.filtered_no_movements, categoryLabel),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = stringResource(R.string.try_other_category_invoices),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = onClearFilter) {
            Text(stringResource(R.string.view_all))
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
    val language = LocalLocale.current.platformLocale.language

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
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(
                             text = TransactionCategories.displayCategory(invoice.categoria, language) +
                                 invoice.subcategoria?.takeIf { it.isNotBlank() }?.let { " / ${TransactionCategories.displayCategory(it, language)}" }.orEmpty(),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
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
                        text = if (invoice.tipo == InvoiceType.GASTO) stringResource(R.string.invoice_type_expense) else stringResource(R.string.invoice_type_income),
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
                            text = stringResource(R.string.invoice_nif_issuer, it),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    invoice.nifReceptor?.let {
                        Text(
                            text = stringResource(R.string.invoice_nif_receiver, it),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (invoice.numeroFactura != null || invoice.baseImponible != null || invoice.cuotaIva != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    invoice.numeroFactura?.let {
                        Text(stringResource(R.string.invoice_number_label, it), style = MaterialTheme.typography.bodySmall)
                    }
                    if (invoice.baseImponible != null || invoice.cuotaIva != null) {
                        Text(
                            text = buildString {
                                invoice.baseImponible?.let {
                                    append(stringResource(R.string.base_vat, com.gastos.domain.model.formatMoney(it, invoice.moneda)))
                                }
                                invoice.cuotaIva?.let {
                                    if (isNotEmpty()) append(" · ")
                                    append(stringResource(R.string.vat_line, com.gastos.domain.model.formatMoney(it, invoice.moneda)))
                                }
                            },
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
                        Text(stringResource(R.string.open_drive_photo))
                    }
                    else -> OutlinedButton(
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
                                 isUploadingToDrive -> stringResource(R.string.drive_uploading)
                                 !isPremium -> stringResource(R.string.drive_requires_premium)
                                 invoice.driveUploadPending -> stringResource(R.string.retry_drive)
                                 else -> stringResource(R.string.upload_to_drive)
                            }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.edit),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = { showDeleteDialog = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_invoice_title)) },
            text = { Text(stringResource(R.string.delete_invoice_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    }
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

private fun openTrustedUrl(
    context: Context,
    rawUrl: String,
    allowedHosts: Set<String>,
    onError: (String) -> Unit
) {
    val uri = rawUrl.toUri()
    val host = uri.host?.lowercase(Locale.ROOT)
    if (uri.scheme != "https" || host !in allowedHosts) {
        onError(context.getString(R.string.open_link_invalid))
        return
    }
    val intent = Intent(Intent.ACTION_VIEW, uri)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        onError(context.getString(R.string.open_link_no_app))
    } catch (_: SecurityException) {
        onError(context.getString(R.string.open_link_denied))
    }
}
