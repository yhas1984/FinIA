package com.gastos.feature.chatbot

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.common.LocalizedNumbers
import com.gastos.data.local.entity.DocumentDraftEntity
import com.gastos.domain.model.*
import com.gastos.feature.ai.AIService
import com.gastos.feature.ai.DocumentReadResult
import com.gastos.feature.backup.SheetsSyncManager
import com.gastos.storage.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.Locale
import javax.inject.Inject

data class CaptureUiState(
    val drafts: List<DocumentDraftEntity> = emptyList(),
    val selected: DocumentDraftEntity? = null,
    val evidence: DocumentEvidence? = null,
    val issues: List<ReviewIssue> = emptyList(),
    val duplicates: List<DuplicateMatch> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val saved: DocumentIdentity? = null
)

@HiltViewModel
class DocumentCaptureViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reader: AIService,
    private val store: DocumentCaptureStore,
    private val images: InvoiceImageStorage,
    private val sheets: SheetsSyncManager
) : ViewModel() {
    private val mutableState: MutableStateFlow<CaptureUiState> = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = mutableState.asStateFlow()
    private var operation: Job? = null

    init {
        viewModelScope.launch { store.observe().collect { drafts -> mutableState.update { it.copy(drafts = drafts) } } }
        viewModelScope.launch { store.indexExistingImages() }
    }

    fun processImage(uri: Uri) {
        if (state.value.busy) return
        mutableState.update { it.copy(selected = null, evidence = null, issues = emptyList(), message = null, duplicates = emptyList(), saved = null) }
        launchOperation {
            when (val start: CaptureStart = store.start(uri)) {
                is CaptureStart.Duplicate -> mutableState.update { it.copy(
                    selected = null, evidence = null, duplicates = start.records.map { record -> DuplicateMatch(record, DuplicateStrength.STRONG, DuplicateReason.SAME_FILE) }) }
                is CaptureStart.Draft -> {
                    images.deleteTemporaryCameraCopy(uri)
                    select(start.value)
                    if (!start.resumed || start.value.evidenceJson == null) read(start.value)
                    else saveExtracted(start.value)
                }
            }
        }
    }

    fun open(draft: DocumentDraftEntity) {
        if (state.value.busy) return
        launchOperation {
            val current: DocumentDraftEntity = store.get(draft.uuid) ?: run { clearSelection(); return@launchOperation }
            select(current)
            saveExtracted(current)
        }
    }

    private suspend fun saveExtracted(draft: DocumentDraftEntity) {
        val evidence: DocumentEvidence = state.value.evidence ?: run {
            mutableState.update { it.copy(message = it.message ?: context.getString(R.string.capture_read_failed)) }
            return
        }
        save(draft.uuid, evidence)
    }

    private fun launchOperation(block: suspend () -> Unit) {
        mutableState.update { it.copy(busy = true) }
        operation = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) {
                mutableState.update { it.copy(message = if (it.selected != null) context.getString(R.string.capture_cancelled) else it.message) }
                throw cancelled
            }
            catch (_: Exception) { mutableState.update { it.copy(message = context.getString(R.string.capture_storage_error)) } }
            finally { mutableState.update { it.copy(busy = false) } }
        }.also { job ->
            // Also release the UI if cancellation happens before the coroutine starts.
            job.invokeOnCompletion { if (operation === job) mutableState.update { it.copy(busy = false) } }
        }
    }

    private fun select(draft: DocumentDraftEntity) {
        val evidence: DocumentEvidence? = DocumentEvidenceCodec.decode(draft.evidenceJson)
        mutableState.update { it.copy(selected = draft, evidence = evidence,
            issues = emptyList(),
            duplicates = emptyList(), message = draft.error, saved = null) }
    }

    private suspend fun read(draft: DocumentDraftEntity, profile: OcrProfile = OcrProfile.FAST) {
        when (val result: DocumentReadResult = reader.readDocument(Uri.parse(draft.imageUri), profile)) {
            is DocumentReadResult.Ready -> {
                val evidence: DocumentEvidence = result.evidence.copy(sourceSha256 = draft.sourceSha256)
                store.update(draft.uuid, evidence)
                mutableState.update { it.copy(evidence = evidence, issues = emptyList()) }
                save(draft.uuid, evidence)
            }
            is DocumentReadResult.NeedsReview -> {
                val evidence: DocumentEvidence = result.evidence.copy(sourceSha256 = draft.sourceSha256)
                store.update(draft.uuid, evidence)
                mutableState.update { it.copy(evidence = evidence, issues = emptyList(), message = null) }
                save(draft.uuid, evidence)
            }
            is DocumentReadResult.Duplicate -> mutableState.update { it.copy(duplicates = result.matches) }
            is DocumentReadResult.Failure -> {
                store.update(draft.uuid, null, result.message)
                mutableState.update { it.copy(message = result.message) }
            }
        }
    }

    fun reread() {
        val draft: DocumentDraftEntity = state.value.selected ?: return
        if (state.value.busy) return
        val rereadDuplicate: Boolean = state.value.duplicates.isNotEmpty()
        mutableState.update { it.copy(busy = true, duplicates = emptyList(), message = null) }
        launchOperation {
            val evidence: DocumentEvidence? = state.value.evidence
            if (!rereadDuplicate && evidence != null && DocumentExtraction.amount(evidence.document) != null) save(draft.uuid, evidence)
            else read(draft)
        }
    }

    fun confirmDistinct() {
        val draft: DocumentDraftEntity = state.value.selected ?: return
        val current: DocumentEvidence = state.value.evidence ?: return
        val matches: List<DuplicateMatch> = state.value.duplicates
        if (state.value.busy || matches.isEmpty() || matches.any { it.strength == DuplicateStrength.STRONG }) return
        val evidence: DocumentEvidence = current.copy(distinctFrom = current.distinctFrom + matches.map { it.existing.version })
        launchOperation { store.update(draft.uuid, evidence); save(draft.uuid, evidence) }
    }

    private suspend fun save(uuid: String, evidence: DocumentEvidence) {
        when (val result: CaptureSave = store.save(uuid, evidence)) {
            is CaptureSave.Duplicate -> mutableState.update { it.copy(duplicates = result.matches) }
            CaptureSave.UnreadableAmount -> mutableState.update { it.copy(message = context.getString(R.string.capture_amount_unreadable)) }
            CaptureSave.MissingDraft -> mutableState.update { it.copy(selected = null, evidence = null) }
            is CaptureSave.Saved -> {
                val identity: DocumentIdentity? = result.invoice?.documentIdentity() ?: result.income?.documentIdentity()
                mutableState.update { it.copy(selected = null, evidence = null, duplicates = emptyList(), issues = emptyList(),
                    saved = identity, message = context.getString(R.string.capture_saved)) }
                // Local success remains success if remote enqueue fails. Startup reconciliation recovers it.
                viewModelScope.launch { try {
                    result.invoice?.let { sheets.syncExpense(it, evidence.toInvoice(uuid, it.imagenUri!!).second) }
                    result.income?.let { sheets.upsertIncome(it) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Durable movement already saved. */ } }
            }
        }
    }

    fun cancelReading() { operation?.cancel() }
    fun dismissNotice() {
        if (!state.value.busy) clearSelection()
    }
    private fun clearSelection() {
        mutableState.update { it.copy(selected = null, evidence = null, issues = emptyList(), duplicates = emptyList(), message = null, saved = null) }
    }
    fun discard() {
        if (state.value.busy) return
        val uuid: String = state.value.selected?.uuid ?: return
        launchOperation { store.discard(uuid); clearSelection() }
    }
}

internal object DocumentEditor {
    private val numeric: Set<String> = setOf("total", "taxBase", "vatAmount", "vatPercent", "withholdingPercent", "withholdingAmount", "discount",
        "gross", "net", "contributionBase", "socialSecurity", "quantity", "unitPrice", "subtotal", "rate", "base", "amount",
        "totalDeductions", "unitRate")

    fun removeLine(evidence: DocumentEvidence, removed: Int): DocumentEvidence {
        fun shift(field: String): String? {
            if (!field.startsWith("lines.")) return field
            val parts: List<String> = field.split('.')
            val index: Int = parts[1].toInt()
            return when { index == removed -> null; index < removed -> field; else -> "lines.${index - 1}.${parts.drop(2).joinToString(".")}" }
        }
        return evidence.copy(document = evidence.document.copy(lines = evidence.document.lines.filterIndexed { index, _ -> index != removed }),
            fieldEdits = evidence.fieldEdits.mapNotNull { (key, value) -> shift(key)?.let { it to value } }.toMap(),
            correctedFields = evidence.correctedFields.mapNotNull(::shift).toSet() + "lines",
            derivedFields = evidence.derivedFields.mapNotNull(::shift).toSet(),
            invalidFields = evidence.invalidFields.mapNotNull(::shift).toSet(), distinctFrom = emptySet())
    }

    fun fields(evidence: DocumentEvidence, locale: Locale): Map<String, String> {
        val root: JsonObject = DocumentEvidenceCodec.json.encodeToJsonElement(evidence.document).jsonObject
        val values: MutableMap<String, String> = linkedMapOf()
        fun add(key: String, value: JsonElement) {
            when (value) {
                is JsonObject -> value.forEach { (name, child) -> add(if (key.isBlank()) name else "$key.$name", child) }
                is JsonArray -> value.forEachIndexed { index, child -> add("$key.$index", child) }
                is JsonPrimitive -> values[key] = evidence.fieldEdits[key] ?: if (value is JsonNull) "" else
                    if (key.substringAfterLast('.') in numeric) value.doubleOrNull?.let { LocalizedNumbers.format(it, locale) }.orEmpty() else value.content
            }
        }
        add("", root)
        return values
    }

    fun edit(evidence: DocumentEvidence, field: String, value: String, locale: Locale): DocumentEvidence {
        val root: MutableMap<String, JsonElement> = DocumentEvidenceCodec.json.encodeToJsonElement(evidence.document).jsonObject.toMutableMap()
        val key: String = field.substringAfterLast('.')
        val changed: JsonElement = when {
            value.isBlank() -> JsonNull
            key in numeric -> LocalizedNumbers.parse(value, locale)?.let(::JsonPrimitive) ?: JsonNull
            key in setOf("linesComplete", "taxesComplete") -> value.toBooleanStrictOrNull()?.let(::JsonPrimitive) ?: JsonNull
            key == "currency" || key == "country" -> JsonPrimitive(value.trim().uppercase(Locale.ROOT))
            else -> JsonPrimitive(value.trim())
        }
        fun replaceAt(node: JsonElement, path: List<String>): JsonElement {
            if (path.isEmpty()) return changed
            return when (node) {
                is JsonObject -> JsonObject(node + (path.first() to replaceAt(node.getValue(path.first()), path.drop(1))))
                is JsonArray -> JsonArray(node.toMutableList().apply { val index: Int = path.first().toInt(); set(index, replaceAt(get(index), path.drop(1))) })
                else -> error("Invalid document field")
            }
        }
        val document: ScannedDocument = DocumentEvidenceCodec.json.decodeFromJsonElement(replaceAt(JsonObject(root), field.split('.')))
        val edited: ScannedDocument = if (field == "date") document.copy(payroll = document.payroll?.copy(dateBasis = PayrollDateBasis.MANUAL)) else document
        return evidence.copy(document = edited, fieldEdits = evidence.fieldEdits + (field to value),
            correctedFields = evidence.correctedFields + field, derivedFields = evidence.derivedFields - field,
            invalidFields = if (value.isNotBlank() && changed is JsonNull) evidence.invalidFields + field else evidence.invalidFields - field,
            distinctFrom = emptySet())
    }
}
