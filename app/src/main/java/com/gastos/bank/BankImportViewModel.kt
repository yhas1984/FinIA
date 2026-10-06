package com.gastos.bank

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.*
import com.gastos.local.database.AppDatabase
import com.gastos.repository.CurrencyPreference
import com.gastos.storage.BankImportStore
import com.gastos.storage.BankSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID
import javax.inject.Inject

sealed interface BankOperation {
    data object Idle : BankOperation
    data class Running(val progress: BankProgress? = null) : BankOperation
    data class Completed(val progress: BankProgress? = null) : BankOperation
    data class Cancelled(val progress: BankProgress? = null) : BankOperation
    data class Failed(val code: String) : BankOperation
}

data class BankScreenData(val bank: BankSnapshot = BankSnapshot(emptyList(), emptyList(), emptyList()), val movements: List<BankMovement> = emptyList())

@HiltViewModel
class BankImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context, private val store: BankImportStore,
    database: AppDatabase, private val saved: SavedStateHandle, currencyPreference: CurrencyPreference,
    private val sheets: com.gastos.feature.backup.SheetsSyncManager
) : ViewModel() {
    val defaultCurrency: String = currencyPreference.defaultCurrency.value
    val data = combine(store.snapshots, database.invoiceDao().observeListEntries(InvoiceType.GASTO), database.incomeDao().observeListEntries(), database.invoiceDao().observeListEntries(InvoiceType.INGRESO)) { bank, _, _, _ ->
        BankScreenData(bank, store.movements())
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BankScreenData())
    val accountId = saved.getStateFlow("bank_account", "")
    val batchId = saved.getStateFlow("bank_batch", "")
    val mapping = saved.getStateFlow("bank_mapping", AutomationCodec.json.encodeToString(BankMapping()))
    val table = MutableStateFlow<BankCsvTable?>(null)
    val preview = MutableStateFlow<List<BankPreview>>(emptyList())
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val operation = MutableStateFlow<BankOperation>(BankOperation.Idle)
    val rectification = MutableStateFlow<BankRectification?>(null)
    val editingBatch = saved.getStateFlow("bank_editing_batch", "")
    val delimiter = saved.getStateFlow("bank_delimiter", "AUTO")
    val headerLine = saved.getStateFlow("bank_header", 0)
    private var activeJob: Job? = null
    private val draftId: String = saved.get<String>("bank_draft") ?: UUID.randomUUID().toString().also { saved["bank_draft"] = it }
    private val draft: File get() = File(context.filesDir, "bank-import-drafts/$draftId.csv")
    val sourceFileAvailable = MutableStateFlow(draft.isFile)
    init {
        File(context.filesDir, "bank-import-drafts").listFiles()?.filter { it.name != "$draftId.csv" &&
            System.currentTimeMillis() - it.lastModified() > 7L * 24 * 60 * 60 * 1000 }?.forEach { it.delete() }
        if (draft.isFile) perform { load(false) }
        else if (editingBatch.value.isNotBlank()) perform { table.value = store.source(editingBatch.value) }
    }
    fun selectAccount(id: String) { if (!busy.value) { saved["bank_account"] = id; preview.value = emptyList() } }
    fun selectBatch(id: String) { saved["bank_batch"] = id }
    fun changeMapping(value: BankMapping) { if (!busy.value) { saved["bank_mapping"] = AutomationCodec.json.encodeToString(value); preview.value = emptyList() } }
    fun clearError() { error.value = null }
    fun createAccount(name: String, currency: String) = perform { saved["bank_account"] = store.account(name, currency.trim().uppercase()).id }
    fun read(uri: Uri, consumed: () -> Unit = {}) = perform {
        require(uri.scheme == "content") { "BANK_CSV" }
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= BankCsv.MAX_BYTES) { "BANK_SIZE" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("BANK_CSV")
        draft.parentFile?.mkdirs()
        val temporary = File(draft.path + ".tmp")
        temporary.writeBytes(bytes)
        check(temporary.renameTo(draft)) { "BANK_STORAGE" }
        sourceFileAvailable.value = true
        val parsed = (0..49).firstNotNullOfOrNull { header -> runCatching { BankCsv.read(bytes, headerLine = header) }.getOrNull() } ?: error("BANK_CSV")
        saved["bank_file_name"] = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else "CSV"
        } ?: "CSV"
        saved["bank_mapping"] = AutomationCodec.json.encodeToString(BankCsv.detect(parsed.headers))
        saved["bank_delimiter"] = "AUTO"; saved["bank_header"] = parsed.headerLine
        val existing = data.value.bank.batches.firstOrNull { it.accountId == accountId.value && it.fileHash == parsed.hash }
        saved["bank_editing_batch"] = existing?.id.orEmpty()
        if (existing != null) { saved["bank_batch"] = existing.id; saved["bank_mapping"] = AutomationCodec.json.encodeToString(existing.mapping) }
        table.value = parsed
        preview.value = emptyList()
        withContext(Dispatchers.Main) { consumed() }
    }
    private fun load(detect: Boolean) {
        val parsed = BankCsv.read(draft.readBytes(), delimiter.value.takeUnless { it == "AUTO" }?.first(), headerLine.value)
        table.value = parsed
        if (detect) saved["bank_mapping"] = AutomationCodec.json.encodeToString(BankCsv.detect(parsed.headers))
    }
    private fun account(): BankAccount = data.value.bank.accounts.firstOrNull { it.id == accountId.value } ?: error("BANK_ACCOUNT")
    private fun currentMapping(): BankMapping = AutomationCodec.json.decodeFromString(mapping.value)
    fun preview() = perform { preview.value = BankCsv.preview(requireNotNull(table.value), account(), currentMapping()) }
    fun format(separator: String, header: Int) = perform {
        require(draft.isFile) { "BANK_CSV" }
        val parsed = BankCsv.read(draft.readBytes(), separator.takeUnless { it == "AUTO" }?.first(), header)
        saved["bank_delimiter"] = separator; saved["bank_header"] = header
        saved["bank_mapping"] = AutomationCodec.json.encodeToString(BankCsv.detect(parsed.headers))
        table.value = parsed; preview.value = emptyList()
    }
    fun import(excludeInvalid: Boolean) = perform {
        val id = store.stage(requireNotNull(table.value), account(), currentMapping(), saved["bank_file_name"] ?: "CSV", excludeInvalid)
        saved["bank_batch"] = id
        draft.delete(); sourceFileAvailable.value = false; table.value = null; preview.value = emptyList(); saved["bank_editing_batch"] = ""
    }
    fun editBatch() = perform {
        val batch = data.value.bank.batches.firstOrNull { it.id == batchId.value } ?: error("BANK_MOVEMENT_CHANGED")
        val source = store.source(batch.id) ?: error("BANK_SOURCE_MISSING")
        saved["bank_account"] = batch.accountId; saved["bank_mapping"] = AutomationCodec.json.encodeToString(batch.mapping)
        saved["bank_editing_batch"] = batch.id
        table.value = source; preview.value = emptyList()
    }
    fun requestRectification(excludeInvalid: Boolean) = perform {
        rectification.value = store.rectification(requireNotNull(table.value), account(), currentMapping(), excludeInvalid)
    }
    fun dismissRectification() { if (!busy.value) rectification.value = null }
    fun rectify(excludeInvalid: Boolean) = perform {
        val current = store.rectification(requireNotNull(table.value), account(), currentMapping(), excludeInvalid)
        require(current == rectification.value) { "BANK_MOVEMENT_CHANGED" }
        for (id in current.reversibleCreatedIds) undoCreated(id)
        saved["bank_batch"] = store.rectify(requireNotNull(table.value), account(), currentMapping(), excludeInvalid)
        rectification.value = null; draft.delete(); sourceFileAvailable.value = false; table.value = null; preview.value = emptyList(); saved["bank_editing_batch"] = ""
    }
    fun cancelFile() { if (!busy.value) { draft.delete(); sourceFileAvailable.value = false; table.value = null; preview.value = emptyList(); error.value = null; saved["bank_editing_batch"] = ""; rectification.value = null } }
    fun create(row: BankTransaction) = perform { store.create(row.id, allowSeparate = true) }
    fun link(row: BankTransaction, movement: BankMovement) = perform { store.link(row.id, movement) }
    fun duplicate(row: BankTransaction, other: BankTransaction) = perform { store.duplicate(row.id, other.id) }
    fun classify(row: BankTransaction, value: BankResolution) = perform { store.classify(row.id, value) }
    fun reopen(row: BankTransaction) = perform { store.reopen(row.id) }
    fun undo(row: BankTransaction) = perform { undoCreated(row.id) }
    private suspend fun undoCreated(id: String) {
        val expected = store.createdRecord(id)
        val prepared = expected.invoice?.let { sheets.prepareExpenseDelete(it) } ?: expected.income?.let { sheets.prepareIncomeDelete(it) }
        store.undoCreated(expected)
        try { sheets.confirmDelete(prepared) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Prepared deletion is recovered by the existing outbox. */ }
    }
    fun createNew() = perform {
        store.createSafeNew(batchId.value) { progress -> operation.value = BankOperation.Running(progress) }
    }
    fun cancelOperation() { activeJob?.cancel() }
    private fun perform(action: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true; error.value = null
        operation.value = BankOperation.Running()
        activeJob = viewModelScope.launch {
            try { withContext(Dispatchers.IO) { action() }; operation.value = BankOperation.Completed((operation.value as? BankOperation.Running)?.progress) }
            catch (cancelled: CancellationException) { operation.value = BankOperation.Cancelled((operation.value as? BankOperation.Running)?.progress); throw cancelled }
            catch (failure: Exception) { error.value = failure.message?.takeIf { it.startsWith("BANK_") } ?: "BANK_STORAGE"; operation.value = BankOperation.Failed(requireNotNull(error.value)) }
            finally { busy.value = false }
        }
    }
}
