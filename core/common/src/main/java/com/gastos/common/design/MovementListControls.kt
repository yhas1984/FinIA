package com.gastos.common.design

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gastos.common.R
import com.gastos.domain.model.ConversionSummary
import com.gastos.domain.model.TransactionCategories

@Composable
fun MovementListHeader(title: String, amount: Double?, currency: String, summary: ConversionSummary?,
    onRefresh: () -> Unit, query: String, onQueryChange: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(query.isNotBlank()) }
    var requestFocus by remember { mutableStateOf(false) }
    val focus: FocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    MoneySummary(title, amount, currency, summary, onRefresh) {
        IconButton(onClick = { expanded = true; requestFocus = true }) {
            Icon(Icons.Default.Search, stringResource(R.string.movements_search))
        }
    }
    LaunchedEffect(requestFocus) {
        if (requestFocus) { focus.requestFocus(); requestFocus = false }
    }
    if (expanded || query.isNotBlank()) OutlinedTextField(
        value = query, onValueChange = onQueryChange, singleLine = true,
        label = { Text(stringResource(R.string.movements_search)) },
        placeholder = { Text(stringResource(R.string.movements_search_hint)) },
        shape = EssentialLayout.fieldShape, colors = essentialFieldColors(),
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = { IconButton(onClick = { onQueryChange(""); expanded = false; focusManager.clearFocus() }) {
            Icon(Icons.Default.Close, stringResource(R.string.movements_search_close))
        } }, modifier = Modifier.fillMaxWidth().padding(horizontal = EssentialLayout.screenPadding, vertical = 4.dp).focusRequester(focus)
    )
}

fun formatMovementCategory(category: String?, subcategory: String?, language: String): String {
    val parent: String = TransactionCategories.displayCategory(category, language)
    val child: String? = TransactionCategories.normalizeCategory(subcategory)?.takeUnless {
        val key: String? = TransactionCategories.normalizeKey(it)
        it.isBlank() || TransactionCategories.isUncategorized(it) ||
            key == TransactionCategories.normalizeKey(TransactionCategories.NO_SUBCATEGORY_LABEL_ES) ||
            key == TransactionCategories.normalizeKey(TransactionCategories.NO_SUBCATEGORY_LABEL_EN)
    }
    return child?.let { "$parent / ${TransactionCategories.displayCategory(it, language)}" } ?: parent
}

@Composable
fun MovementPageFooter(shown: Int, total: Int, hasMore: Boolean, onMore: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(EssentialLayout.screenPadding)) {
        val label: String = if (hasMore || shown < total) stringResource(R.string.movements_result_count, shown, total)
            else pluralStringResource(R.plurals.movements_count, total, total)
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (hasMore) OutlinedButton(onClick = onMore, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.movements_load_more))
        }
    }
}
