package com.gastos.feature.invoices

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gastos.domain.model.*
import com.gastos.common.design.*
import java.text.SimpleDateFormat
import java.util.*

private const val UNCATEGORIZED_FILTER = "__uncategorized__"

@Composable
fun InvoicesScreen(
    onNavigateToEdit: (Long) -> Unit,
    onOpenMovement: ((MovementReference) -> Unit)? = null,
    viewModel: InvoicesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val language = LocalLocale.current.platformLocale.language
    val listState = rememberLazyListState()
    Scaffold(topBar = { EssentialHeader(stringResource(R.string.invoices_title)) }) { padding ->
        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
                item { MovementListHeader(stringResource(R.string.total_expenses), uiState.totalGastosConvertido, uiState.defaultCurrency, uiState.conversion, viewModel::refreshRates, uiState.query, viewModel::search) }
                item { PeriodSelector(uiState.periodStart, uiState.periodEnd, viewModel::filterByPeriod) }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(uiState.selectedCategoryFilter == null, { viewModel.filterByCategory(null) }, label = { Text(stringResource(R.string.all_items)) })
                        FilterChip(uiState.selectedCategoryFilter == UNCATEGORIZED_FILTER, { viewModel.filterByCategory(UNCATEGORIZED_FILTER) }, label = { Text(TransactionCategories.currentUncategorizedLabel(language)) })
                        uiState.availableCategories.forEach { category ->
                            FilterChip(uiState.selectedCategoryFilter == category, { viewModel.filterByCategory(category) }, label = { Text(TransactionCategories.displayCategory(category, language)) })
                        }
                    }
                }
                if (uiState.availableSubcategories.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(uiState.selectedSubcategoryFilter == null, { viewModel.filterBySubcategory(null) }, label = { Text(stringResource(R.string.all_items)) })
                        uiState.availableSubcategories.forEach { sub ->
                            FilterChip(uiState.selectedSubcategoryFilter == sub, { viewModel.filterBySubcategory(sub) }, label = { Text(TransactionCategories.displayCategory(sub, language)) })
                        }
                    }
                }
                uiState.error?.let { error -> item { Text(error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
                if (uiState.invoices.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(if (uiState.hasAnyInvoices) com.gastos.common.R.string.movements_no_results else R.string.no_invoices), style = MaterialTheme.typography.titleMedium)
                        if (!uiState.hasAnyInvoices) Text(stringResource(R.string.add_first_invoice), style = MaterialTheme.typography.bodyMedium)
                        else TextButton(onClick = viewModel::clearFilters) { Text(stringResource(R.string.view_all)) }
                    }
                }
                items(uiState.invoices, key = { it.documentUuid }) { record ->
                    MovementRow(record.description, formatMovementCategory(record.category, record.subcategory, language),
                        record.date, record.amount, record.currency, false, onEdit = { onNavigateToEdit(record.id) }) {
                        if (onOpenMovement == null) onNavigateToEdit(record.id) else onOpenMovement(record.movementReference())
                    }
                }
                if (uiState.invoices.isNotEmpty()) item { MovementPageFooter(uiState.invoices.size, uiState.matchingCount, uiState.hasMore, viewModel::loadMore) }
            }
        }
    }
}

@Composable
fun InvoiceDetailContent(
    invoice: Invoice,
    
    dateFormat: SimpleDateFormat,
    onDelete: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRetryDrive: () -> Unit,
    isPremium: Boolean,
    isUploadingToDrive: Boolean,
    enabled: Boolean = true
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteRemoteImage by remember { mutableStateOf(false) }
    val language = LocalLocale.current.platformLocale.language

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            MovementHeading(
                title = invoice.proveedor,
                date = dateFormat.format(Date(invoice.fecha)),
                category = TransactionCategories.displayCategory(invoice.categoria, language) +
                    invoice.subcategoria?.takeIf(String::isNotBlank)?.let { " / ${TransactionCategories.displayCategory(it, language)}" }.orEmpty(),
                amount = invoice.total, currency = invoice.moneda, isIncome = invoice.tipo == InvoiceType.INGRESO
            )

            if (invoice.nifEmisor != null || invoice.nifReceptor != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
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
                                    append(stringResource(R.string.base_vat, com.gastos.domain.model.formatMoney(it, invoice.moneda, LocalLocale.current.platformLocale)))
                                }
                                invoice.cuotaIva?.let {
                                    if (isNotEmpty()) append(" · ")
                                    append(stringResource(com.gastos.common.R.string.taxes_total, com.gastos.domain.model.formatMoney(it, invoice.moneda, LocalLocale.current.platformLocale), ""))
                                }
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (invoice.manualAmountAdjusted) Text(stringResource(com.gastos.data.R.string.amount_manually_adjusted), style = MaterialTheme.typography.bodySmall)
            if (invoice.origin.startsWith("WALLET")) Text("Google Wallet", style = MaterialTheme.typography.labelSmall)
            com.gastos.common.TaxBreakdownSummary(invoice.taxes, invoice.moneda)

            com.gastos.feature.backup.DocumentImageButton(
                localUri = invoice.imagenUri, fileId = invoice.driveFileId,
                accountId = invoice.driveAccountId, contentHash = invoice.driveContentHash)

            if (invoice.imagenUri != null && invoice.driveFileId == null) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onEdit, enabled = enabled) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.edit),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = { deleteRemoteImage = false; showDeleteDialog = true }, enabled = enabled) {
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
            text = { Column {
                Text(stringResource(R.string.delete_invoice_message))
                if (invoice.driveFileId != null && invoice.driveAccountId != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = deleteRemoteImage, onCheckedChange = { deleteRemoteImage = it })
                        Text(stringResource(com.gastos.feature.backup.R.string.delete_remote_image_option))
                    }
                }
            } },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(deleteRemoteImage)
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
