package com.gastos.feature.incomes

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
fun IncomesScreen(
    onNavigateToEdit: (Long) -> Unit,
    onOpenMovement: ((MovementReference) -> Unit)? = null,
    viewModel: IncomesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val language = LocalLocale.current.platformLocale.language
    val listState = rememberLazyListState()
    Scaffold(topBar = { EssentialHeader(stringResource(R.string.incomes_title)) }) { padding ->
        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
                item { MovementListHeader(stringResource(R.string.total_incomes), uiState.totalIngresosConvertido, uiState.defaultCurrency, uiState.conversion, viewModel::refreshRates, uiState.query, viewModel::search) }
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
                if (uiState.incomes.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(if (uiState.hasAnyIncomes) com.gastos.common.R.string.movements_no_results else R.string.no_incomes), style = MaterialTheme.typography.titleMedium)
                        if (!uiState.hasAnyIncomes) Text(stringResource(R.string.add_first_income), style = MaterialTheme.typography.bodyMedium)
                        else TextButton(onClick = viewModel::clearFilters) { Text(stringResource(R.string.view_all)) }
                    }
                }
                items(uiState.incomes, key = { it.documentUuid }) { record ->
                    MovementRow(record.description, formatMovementCategory(record.category, record.subcategory, language),
                        record.date, record.amount, record.currency, true, onEdit = { onNavigateToEdit(record.id) }) {
                        if (onOpenMovement == null) onNavigateToEdit(record.id) else onOpenMovement(record.movementReference())
                    }
                }
                if (uiState.incomes.isNotEmpty()) item { MovementPageFooter(uiState.incomes.size, uiState.matchingCount, uiState.hasMore, viewModel::loadMore) }
            }
        }
    }
}

@Composable
fun IncomeDetailContent(
    income: Income,
    
    dateFormat: SimpleDateFormat,
    onDelete: (Boolean) -> Unit,
    onEdit: () -> Unit,
    enabled: Boolean = true
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteRemoteImage by remember { mutableStateOf(false) }
    val language = LocalLocale.current.platformLocale.language

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            MovementHeading(
                title = income.concepto,
                date = dateFormat.format(Date(income.fecha)),
                category = TransactionCategories.displayCategory(income.categoria, language) +
                    income.subcategoria?.takeIf(String::isNotBlank)?.let { " / ${TransactionCategories.displayCategory(it, language)}" }.orEmpty(),
                amount = income.monto, currency = income.moneda, isIncome = true,
                source = income.fuente?.let { stringResource(R.string.source_label, it) }
            )

            if (income.manualAmountAdjusted) Text(stringResource(com.gastos.data.R.string.amount_manually_adjusted), style = MaterialTheme.typography.bodySmall)
            if (income.origin.startsWith("WALLET")) Text("Google Wallet", style = MaterialTheme.typography.labelSmall)
            com.gastos.common.TaxBreakdownSummary(income.taxes, income.moneda)
            if (income.evidence?.document?.payroll?.dateBasis == com.gastos.domain.model.PayrollDateBasis.PERIOD_END) {
                Text(stringResource(com.gastos.data.R.string.document_chat_period_date),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            com.gastos.feature.backup.DocumentImageButton(
                localUri = income.imagenUri, fileId = income.driveFileId,
                accountId = income.driveAccountId, contentHash = income.driveContentHash)

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
            title = { Text(stringResource(R.string.delete_income_title)) },
            text = { Column {
                Text(stringResource(R.string.delete_income_message))
                if (income.driveFileId != null && income.driveAccountId != null) {
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
