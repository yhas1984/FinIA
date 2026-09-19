package com.gastos.feature.backup

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gastos.repository.InvoiceRepository
import com.gastos.repository.IncomeRepository
import com.gastos.storage.InvoiceImageStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ImageSyncDisplayStatus { SYNCED, PENDING, FAILED, MISSING }
data class ImageSyncRow(val id: Long, val uuid: String, val target: RemoteSyncTarget, val label: String,
    val status: ImageSyncDisplayStatus, val error: String?)

@HiltViewModel
class ImageSyncViewModel @Inject constructor(
    invoices: InvoiceRepository, incomes: IncomeRepository,
    private val outbox: RemoteSyncOutboxRepository, private val storage: InvoiceImageStorage
) : ViewModel() {
    val rows = combine(invoices.getAllInvoices(), incomes.getAllIncomes(), outbox.operations) { expenses, income, operations ->
        fun row(id: Long, uuid: String, target: RemoteSyncTarget, label: String, local: String?, remote: String?,
            pending: Boolean, error: String?): ImageSyncRow? {
            if (local == null && remote == null && error == null) return null
            val job = operations.firstOrNull { it.target == target && it.documentUuid == uuid && it.action == RemoteSyncAction.UPSERT }
            val status = when {
                storage.managedFile(local) == null && (remote == null || pending && error == "MISSING_SOURCE") -> ImageSyncDisplayStatus.MISSING
                job?.status == RemoteSyncStatus.FAILED -> ImageSyncDisplayStatus.FAILED
                remote != null && !pending -> ImageSyncDisplayStatus.SYNCED
                else -> ImageSyncDisplayStatus.PENDING
            }
            return ImageSyncRow(id, uuid, target, label, status, job?.lastError ?: error)
        }
        expenses.mapNotNull { row(it.id, it.documentUuid, RemoteSyncTarget.INVOICE_DRIVE, it.proveedor,
            it.imagenUri, it.driveFileId, it.driveUploadPending, it.driveSyncError) } +
            income.mapNotNull { row(it.id, it.documentUuid, RemoteSyncTarget.INCOME_DRIVE, it.concepto,
                it.imagenUri, it.driveFileId, it.driveUploadPending, it.driveSyncError) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun retry(row: ImageSyncRow? = null) {
        viewModelScope.launch {
            (row?.let(::listOf) ?: rows.value).filter {
                it.status == ImageSyncDisplayStatus.PENDING || it.status == ImageSyncDisplayStatus.FAILED
            }.forEach { outbox.enqueue(it.target, it.id, RemoteSyncAction.UPSERT, documentUuid = it.uuid) }
        }
    }
}

@Composable
fun ImageSyncStatusPanel(viewModel: ImageSyncViewModel = hiltViewModel()) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    var details by remember { mutableStateOf(false) }
    var visibleCount by remember { mutableIntStateOf(20) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.image_sync_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.image_sync_counts,
                rows.count { it.status == ImageSyncDisplayStatus.SYNCED }, rows.count { it.status == ImageSyncDisplayStatus.PENDING },
                rows.count { it.status == ImageSyncDisplayStatus.FAILED }, rows.count { it.status == ImageSyncDisplayStatus.MISSING }))
            Text(stringResource(R.string.image_sync_data_independent), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { details = !details }) { Text(stringResource(R.string.image_sync_details)) }
            if (rows.any { it.status == ImageSyncDisplayStatus.PENDING || it.status == ImageSyncDisplayStatus.FAILED }) {
                OutlinedButton(onClick = { viewModel.retry() }) { Text(stringResource(R.string.image_sync_retry_all)) }
            }
            if (details) {
                val unresolved = rows.filter { it.status != ImageSyncDisplayStatus.SYNCED }
                unresolved.take(visibleCount).forEach { row ->
                    Text(row.label, style = MaterialTheme.typography.titleSmall)
                    Text(row.error?.let { imageErrorMessage(it) } ?: stringResource(R.string.image_sync_pending))
                    if (row.status != ImageSyncDisplayStatus.MISSING)
                        TextButton(onClick = { viewModel.retry(row) }) { Text(stringResource(R.string.image_retry)) }
                }
                if (unresolved.size > visibleCount) TextButton(onClick = { visibleCount += 20 }) { Text(stringResource(R.string.image_sync_details)) }
            }
        }
    }
}
