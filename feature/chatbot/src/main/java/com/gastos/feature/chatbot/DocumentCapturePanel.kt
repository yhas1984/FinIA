package com.gastos.feature.chatbot

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gastos.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Validation stays in the capture pipeline. Only user actions open dialogs. */
@Composable
internal fun DocumentCapturePanel(state: CaptureUiState, model: DocumentCaptureViewModel, onOpen: (DocumentIdentity) -> Unit) {
    var showDrafts: Boolean by remember { mutableStateOf(false) }
    if (state.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.capture_processing), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = model::cancelReading) { Text(stringResource(R.string.capture_cancel_read)) }
        }
    } else if (state.selected != null || state.duplicates.isNotEmpty()) {
        PendingDocumentNotice(state, model, onOpen)
    } else state.message?.let { message ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = model::dismissNotice) { Icon(Icons.Default.Close, stringResource(R.string.capture_hide_notice)) }
        }
    }
    Row {
        if (state.drafts.isNotEmpty()) TextButton(onClick = { showDrafts = true }, enabled = !state.busy) {
            Text(stringResource(R.string.capture_pending, state.drafts.size))
        }
        state.saved?.let { record -> TextButton(onClick = { onOpen(record) }) { Text(stringResource(R.string.capture_open)) } }
    }
    if (showDrafts) AlertDialog(onDismissRequest = { showDrafts = false }, title = { Text(stringResource(R.string.capture_drafts)) },
        text = { LazyColumn { items(state.drafts, key = { it.uuid }) { draft ->
            TextButton(onClick = { model.open(draft); showDrafts = false }) {
                val evidence: DocumentEvidence? = DocumentEvidenceCodec.decode(draft.evidenceJson)
                Text(evidence?.document?.issuer ?: stringResource(R.string.capture_unread))
            }
        } } }, confirmButton = { TextButton(onClick = { showDrafts = false }) { Text(stringResource(R.string.capture_close)) } })
}

@Composable
private fun PendingDocumentNotice(state: CaptureUiState, model: DocumentCaptureViewModel, onOpen: (DocumentIdentity) -> Unit) {
    val context = LocalContext.current
    var showOptions: Boolean by remember(state.selected?.uuid) { mutableStateOf(false) }
    var showMatches: Boolean by remember(state.selected?.uuid) { mutableStateOf(false) }
    var confirmDiscard: Boolean by remember(state.selected?.uuid) { mutableStateOf(false) }
    val hasMatches: Boolean = state.duplicates.isNotEmpty()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("capture_status")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (hasMatches) R.string.capture_duplicate else R.string.capture_pending_attention), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = model::dismissNotice) { Icon(Icons.Default.Close, stringResource(R.string.capture_hide_notice)) }
            }
            val taxIssues = state.issues.filter { it.field.startsWith("taxes") || it.field.endsWith("vatAmount") || it.field.contains(".taxes") }
            val explanation = when {
                hasMatches -> R.string.capture_duplicate_retained
                taxIssues.any { it.reason == ReviewReason.INCONSISTENT || it.reason == ReviewReason.INVALID } -> R.string.capture_tax_mismatch
                taxIssues.isNotEmpty() -> R.string.capture_tax_unreadable
                else -> R.string.capture_pending_explanation
            }
            Text(state.message ?: stringResource(explanation),
                style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hasMatches) TextButton(onClick = { showMatches = true }) { Text(stringResource(R.string.capture_view_matches)) }
                else TextButton(onClick = model::reread) { Text(stringResource(R.string.capture_retry)) }
                if (state.selected != null) Box {
                    IconButton(onClick = { showOptions = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.capture_options)) }
                    DropdownMenu(expanded = showOptions, onDismissRequest = { showOptions = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.capture_view_photo)) }, onClick = {
                            showOptions = false
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW)
                                .setDataAndType(Uri.parse(state.selected.imageUri), "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                        })
                        if (hasMatches) DropdownMenuItem(text = { Text(stringResource(R.string.capture_retry)) },
                            onClick = { showOptions = false; model.reread() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.capture_discard)) },
                            onClick = { showOptions = false; confirmDiscard = true })
                    }
                }
            }
        }
    }
    if (showMatches) DuplicateDialog(state, onOpen, onDismiss = { showMatches = false }, onDistinct = {
        showMatches = false
        model.confirmDistinct()
    })
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
        text = { Text(stringResource(R.string.capture_discard_explanation)) },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; model.discard() }) { Text(stringResource(R.string.capture_discard)) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.capture_close)) } })
}

@Composable
private fun DuplicateDialog(state: CaptureUiState, onOpen: (DocumentIdentity) -> Unit, onDismiss: () -> Unit, onDistinct: () -> Unit) {
    val canConfirm: Boolean = state.selected != null && state.evidence != null && state.issues.isEmpty() &&
        state.duplicates.isNotEmpty() && state.duplicates.none { it.strength == DuplicateStrength.STRONG }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.capture_duplicate)) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            DuplicateRecords(state.duplicates) { record -> onDismiss(); onOpen(record) }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.capture_close)) } },
        dismissButton = { if (canConfirm) TextButton(onClick = onDistinct) { Text(stringResource(R.string.capture_distinct)) } })
}

@Composable
internal fun DocumentPhoto(uri: String) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 })
            }
        }.getOrNull() }
    }
    bitmap?.let { image -> Image(image.asImageBitmap(), contentDescription = stringResource(R.string.capture_photo),
        modifier = Modifier.fillMaxWidth().height(180.dp).clickable {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        }) }
}

@Composable
private fun DuplicateRecords(matches: List<DuplicateMatch>, onOpen: (DocumentIdentity) -> Unit) {
    matches.forEach { match ->
        val record = match.existing
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("${record.issuer} · ${record.number.orEmpty()}")
                Text("${record.date} · ${record.amount} ${record.currency}")
                Text(stringResource(when (match.reason) {
                    DuplicateReason.SAME_FILE -> R.string.capture_match_file
                    DuplicateReason.SAME_INVOICE -> R.string.capture_match_invoice
                    DuplicateReason.SAME_PAYROLL -> R.string.capture_match_payroll
                    DuplicateReason.CONFLICT -> R.string.capture_match_conflict
                    DuplicateReason.SIMILAR_NUMBER -> R.string.capture_match_similar
                }))
                TextButton(onClick = { onOpen(record) }) { Text(stringResource(R.string.capture_open)) }
            }
        }
    }
}

@Composable
internal fun captureReasonLabel(reason: ReviewReason): String = stringResource(when (reason) {
    ReviewReason.MISSING -> R.string.capture_missing
    ReviewReason.INVALID -> R.string.capture_invalid
    ReviewReason.INCONSISTENT -> R.string.capture_inconsistent
    ReviewReason.AMBIGUOUS -> R.string.capture_ambiguous
    ReviewReason.INCOMPLETE -> R.string.capture_incomplete
})

@Composable
internal fun captureOptionLabel(option: String): String = stringResource(when (option) {
    "factura_recibida" -> R.string.capture_received
    "factura_emitida" -> R.string.capture_issued
    "ticket" -> R.string.capture_ticket
    "recibo" -> R.string.capture_receipt
    "nomina" -> R.string.capture_payroll
    "tax_included" -> R.string.capture_tax_included
    "tax_excluded" -> R.string.capture_tax_excluded
    "true" -> R.string.capture_yes
    else -> R.string.capture_no
})

@Composable
internal fun captureFieldLabel(field: String): String {
    val label = stringResource(when (field.substringAfterLast('.')) {
        "kind" -> R.string.capture_field_kind
        "country" -> R.string.capture_field_country
        "currency" -> R.string.capture_field_currency
        "date" -> R.string.capture_field_date
        "number" -> R.string.capture_field_number
        "issuer" -> R.string.capture_field_issuer
        "issuerTaxId" -> R.string.capture_field_issuerTaxId
        "recipientTaxId" -> R.string.capture_field_recipientTaxId
        "category" -> R.string.capture_field_category
        "subcategory" -> R.string.capture_field_subcategory
        "total" -> R.string.capture_field_total
        "taxBase" -> R.string.capture_field_taxBase
        "vatAmount" -> R.string.capture_field_vatAmount
        "vatPercent" -> R.string.capture_field_vatPercent
        "withholdingPercent" -> R.string.capture_field_withholdingPercent
        "withholdingAmount" -> R.string.capture_field_withholdingAmount
        "discount" -> R.string.capture_field_discount
        "priceBasis" -> R.string.capture_field_priceBasis
        "gross" -> R.string.capture_field_gross
        "net" -> R.string.capture_field_net
        "contributionBase" -> R.string.capture_field_contributionBase
        "socialSecurity" -> R.string.capture_field_socialSecurity
        "payrollReference" -> R.string.capture_field_payrollReference
        "workerId" -> R.string.capture_field_workerId
        "payPeriod" -> R.string.capture_field_payPeriod
        "paymentKind" -> R.string.capture_field_paymentKind
        "linesComplete" -> R.string.capture_field_linesComplete
        "description" -> R.string.capture_field_description
        "quantity" -> R.string.capture_field_quantity
        "unitPrice" -> R.string.capture_field_unitPrice
        "subtotal" -> R.string.capture_field_subtotal
        else -> R.string.capture_field_lines
    })
    return if (field.startsWith("lines.")) "${field.split('.')[1].toInt() + 1}. $label" else label
}
