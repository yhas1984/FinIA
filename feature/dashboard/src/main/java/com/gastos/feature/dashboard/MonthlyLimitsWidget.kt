package com.gastos.feature.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gastos.storage.MonthlyLimitStore
import com.gastos.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.map

@HiltViewModel
class MonthlyLimitsViewModel @Inject constructor(private val store: MonthlyLimitStore) : ViewModel() {
    val hasLimits = store.limits.map { it.isNotEmpty() }
    fun progress(month: String) = store.progress(month)
}

@Composable
fun MonthlyLimitsWidget(month: String, model: MonthlyLimitsViewModel = hiltViewModel()) {
    val progress by remember(month) { model.progress(month) }.collectAsStateWithLifecycle(emptyList())
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.monthly_limits), style = MaterialTheme.typography.titleMedium)
        if (progress.isEmpty()) Text(stringResource(R.string.no_monthly_limits), style = MaterialTheme.typography.bodySmall)
        progress.forEach { entry ->
            Text("${TransactionCategories.displayCategory(entry.category)} · ${entry.spent?.let { formatMoney(it, entry.limit.currency) } ?: stringResource(R.string.total_unavailable)} / ${formatMoney(entry.limit.amount, entry.limit.currency)}")
            entry.ratio?.let { LinearProgressIndicator(progress = { it.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) }
            if (entry.partial) Text(stringResource(R.string.limit_partial, entry.excluded), style = MaterialTheme.typography.bodySmall)
            else entry.remaining?.let { Text(stringResource(if (it >= 0) R.string.limit_remaining else R.string.limit_exceeded, formatMoney(kotlin.math.abs(it), entry.limit.currency)), style = MaterialTheme.typography.bodySmall) }
        }
    }
}
