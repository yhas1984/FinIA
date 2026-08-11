package com.gastos.feature.incomes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gastos.domain.model.Income
import com.gastos.domain.model.TransactionCategories
import com.gastos.feature.incomes.R
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

private const val UNCATEGORIZED_FILTER = "__uncategorized__"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomesScreen(
    onNavigateToEdit: (Long) -> Unit = {},
    viewModel: IncomesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val language = LocalLocale.current.platformLocale.language
    
    val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.forLanguageTag("es-ES"))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.incomes_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
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
        } else if (!uiState.hasAnyIncomes) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.no_incomes),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.add_first_income),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // Resumen total (convertido a la moneda por defecto del usuario).
            val total = uiState.totalIngresosConvertido
            val target = uiState.defaultCurrency

            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                uiState.error?.let { message ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Text(
                            text = message,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.total_incomes),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                        text = if (total != null) {
                            com.gastos.domain.model.formatMoney(total, target)
                        } else {
                                stringResource(R.string.total_unavailable, target)
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
                    if (uiState.incomes.isEmpty()) {
                        item {
                            FilteredEmptyState(
                                category = uiState.selectedCategoryFilter,
                                language = language,
                                onClearFilter = { viewModel.filterByCategory(null) }
                            )
                        }
                    }
                    items(
                        items = uiState.incomes,
                        key = { it.id }
                    ) { income ->
                        IncomeCard(
                            income = income,
                            dateFormat = dateFormat,
                            onDelete = { viewModel.deleteIncome(income) },
                            onEdit = { onNavigateToEdit(income.id) }
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
            text = stringResource(R.string.try_other_category_incomes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = onClearFilter) {
            Text(stringResource(R.string.view_all))
        }
    }
}

@Composable
private fun IncomeCard(
    income: Income,
    
    dateFormat: SimpleDateFormat,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val language = LocalLocale.current.platformLocale.language

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = income.concepto,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = dateFormat.format(Date(income.fecha)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (income.fuente != null) {
                        Text(
                            text = stringResource(R.string.source_label, income.fuente.orEmpty()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(
                             text = TransactionCategories.displayCategory(income.categoria, language) +
                                 income.subcategoria?.takeIf { it.isNotBlank() }?.let { " / ${TransactionCategories.displayCategory(it, language)}" }.orEmpty(),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "+${com.gastos.domain.model.formatMoney(income.monto, income.moneda)}",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        text = income.moneda,
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
            title = { Text(stringResource(R.string.delete_income_title)) },
            text = { Text(stringResource(R.string.delete_income_message)) },
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
