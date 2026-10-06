package com.gastos.automation

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.R
import com.gastos.common.LocalizedNumbers
import com.gastos.domain.model.*
import com.gastos.storage.*
import com.gastos.repository.CurrencyPreference
import com.gastos.repository.InvoiceRepository
import com.gastos.feature.backup.SheetsSyncManager
import com.gastos.local.database.AppDatabase
import com.gastos.data.local.entity.toDomain
import com.gastos.WalletCaptureWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

data class WalletPaymentItem(
    val id: String, val postedAt: Long, val merchant: String?, val amount: Double?, val currency: String?,
    val event: PaymentEvent? = null, val capture: WalletCaptureRecord? = null,
    val movementExists: Boolean = false, val canUndo: Boolean = false
)

@HiltViewModel
class AutomationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalog: CategoryCatalog,
    private val budgetStore: MonthlyLimitStore,
    private val walletStore: WalletPaymentStore,
    private val invoices: InvoiceRepository,
    private val sheets: SheetsSyncManager,
    private val database: AppDatabase,
    private val currencyPreference: CurrencyPreference,
    private val savedState: SavedStateHandle
) : ViewModel() {
    val categories = catalog.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val rules = catalog.rules.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val payments = combine(database.automationDao().observeRecords("PAYMENT"),
        database.automationDao().observeRecords(WalletPaymentPolicy.CAPTURE_TYPE),
        database.invoiceDao().getAllInvoices(), database.automationDao().observeBankRecords()) { rows, pending, records, bank ->
        val linked = bank.filter { it.type == "BANK_ROW" }.mapNotNull {
            AutomationCodec.json.decodeFromString<BankTransaction>(it.payload).documentUuid
        }.toSet()
        val byUuid = records.associateBy { it.documentUuid }
        val complete = rows.map { row ->
            val event: PaymentEvent = AutomationCodec.json.decodeFromString(row.payload)
            val invoice = byUuid[event.documentUuid]?.toDomain()
            WalletPaymentItem(row.id, event.postedAt, event.merchant ?: invoice?.proveedor,
                event.amount ?: invoice?.total, event.currency ?: invoice?.moneda, event = event,
                movementExists = invoice != null, canUndo = WalletPaymentPolicy.canUndo(event, invoice, event.documentUuid in linked))
        }
        (complete + pending.map { row ->
            val capture: WalletCaptureRecord = AutomationCodec.json.decodeFromString(row.payload)
            WalletPaymentItem(row.id, capture.payment.occurredAt, capture.payment.merchant, capture.payment.amount,
                capture.payment.currency, capture = capture)
        }).sortedByDescending { it.postedAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    private val session = OrganizationEditorSession(savedState, viewModelScope, busy, ::persistDraft, ::errorMessage) {
        message.value = context.getString(R.string.automation_saved)
    }
    internal val editor get() = session.editor
    internal val saveState get() = session.result
    internal val hasChanges: Boolean get() = session.hasChanges
    val walletEnabled: Boolean get() = walletStore.enabled
    val walletCaptureSession: String get() = walletStore.captureSession()
    init { viewModelScope.launch { catalog.initialize() } }
    fun wallet(enabled: Boolean): Boolean = try {
        walletStore.setEnabled(enabled)
        if (enabled) WalletCaptureWorker.schedule(context)
        true
    }
        catch (_: Exception) { message.value = context.getString(R.string.automation_invalid); false }
    fun dismissMessage() { message.value = null }
    fun progress(month: String): Flow<List<MonthlyLimitProgress>> = budgetStore.progress(month)

    internal fun openCategory(kind: DocumentKind, parentId: String = "", category: Category? = null) = openEditor(
        OrganizationDraft(OrganizationEditor.CATEGORY, originalId = category?.id.orEmpty(),
            kind = kind, parentId = category?.parentId ?: parentId, name = category?.name.orEmpty()))
    internal fun openRule(kind: DocumentKind, rule: CategoryRule? = null) = openEditor(
        OrganizationDraft(OrganizationEditor.RULE, originalId = rule?.id.orEmpty(), kind = rule?.kind ?: kind,
            parentId = rule?.categoryId.orEmpty(), childId = rule?.subcategoryId.orEmpty(), merchant = rule?.merchant.orEmpty(),
            match = rule?.match ?: RuleMatch.EXACT, priority = (rule?.priority ?: 0).toString(), enabled = rule?.enabled ?: true))
    internal fun openLimit(month: String, locale: Locale, limit: MonthlyLimit? = null) = openEditor(
        OrganizationDraft(OrganizationEditor.LIMIT, originalId = limit?.id.orEmpty(), parentId = limit?.categoryId.orEmpty(),
            month = limit?.month ?: month, amount = limit?.amount?.let { LocalizedNumbers.format(it, locale) }.orEmpty(),
            currency = limit?.currency ?: currencyPreference.defaultCurrency.value, repeat = limit?.repeat ?: false,
            notify = limit?.notify ?: false))
    private fun openEditor(draft: OrganizationDraft) = session.open(draft)
    internal fun updateDraft(draft: OrganizationDraft) = session.update(draft)
    internal fun closeEditor() = session.close()
    internal fun saveEditor(locale: Locale) = session.save(locale)
    private suspend fun persistDraft(draft: OrganizationDraft, locale: Locale) {
        when (draft.editor) {
            OrganizationEditor.CATEGORY -> if (draft.originalId.isEmpty()) catalog.createUnique(draft.kind, draft.name, draft.parentId)
                else catalog.rename(draft.originalId, draft.name)
            OrganizationEditor.RULE -> {
                val priority: Int = draft.priority.toIntOrNull() ?: throw IllegalArgumentException("PRIORITY_INVALID")
                val rule = CategoryRule(draft.originalId.ifEmpty { UUID.randomUUID().toString() }, draft.kind,
                    draft.merchant.trim(), draft.parentId, draft.childId.ifEmpty { null }, draft.match, priority, draft.enabled)
                if (draft.originalId.isEmpty()) catalog.putRule(rule) else catalog.updateRule(rule)
            }
            OrganizationEditor.LIMIT -> {
                val amount: Double = LocalizedNumbers.parse(draft.amount, locale) ?: throw IllegalArgumentException("LIMIT_INVALID")
                val limit = MonthlyLimit(draft.originalId, draft.parentId, draft.month, amount,
                    draft.currency.trim().uppercase(Locale.ROOT), draft.repeat, draft.notify)
                if (draft.originalId.isEmpty()) budgetStore.create(limit) else budgetStore.update(draft.originalId, limit)
            }
        }
    }
    private fun errorMessage(failure: Exception): String = context.getString(when (failure.message) {
        "CATEGORY_ALREADY_EXISTS" -> R.string.category_duplicate
        "LIMIT_ALREADY_EXISTS" -> R.string.limit_conflict
        "CATEGORY_NAME_INVALID" -> R.string.category_name_invalid
        "CATEGORY_PARENT_INVALID", "CATEGORY_INVALID" -> R.string.category_unavailable
        "RULE_INVALID" -> R.string.category_selection_invalid
        "PRIORITY_INVALID" -> R.string.priority_invalid
        "LIMIT_INVALID" -> R.string.limit_invalid
        "CURRENCY_INVALID" -> R.string.currency_invalid
        "RECORD_CHANGED" -> R.string.record_changed
        "PAYMENT_CHANGED" -> R.string.wallet_undo_unavailable
        "WALLET_DISABLED" -> R.string.wallet_retry_disabled
        else -> R.string.automation_invalid
    })
    private fun perform(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { block(); message.value = context.getString(R.string.automation_saved) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message.value = errorMessage(failure) }
            finally { busy.value = false }
        }
    }
    fun archive(category: Category) = perform { catalog.archive(category.id, !category.archived) }
    fun removeRule(rule: CategoryRule) = perform { catalog.deleteRule(rule.id) }
    fun toggleRule(rule: CategoryRule) = perform { catalog.updateRule(rule.copy(enabled = !rule.enabled)) }
    fun removeLimit(limit: MonthlyLimit) = perform { budgetStore.delete(limit.id) }
    fun retryPayment(id: String) = perform { walletStore.retry(id); WalletCaptureWorker.schedule(context, replaceExisting = true) }
    fun ignorePayment(id: String) = perform { walletStore.ignore(id) }
    fun undoPayment(id: String, event: PaymentEvent) = perform {
        val invoice = invoices.getAllInvoices().first().firstOrNull { it.documentUuid == event.documentUuid } ?: error("PAYMENT_CHANGED")
        val prepared = sheets.prepareExpenseDelete(invoice)
        walletStore.undo(id)
        // Local undo is complete; a durable prepared intent recovers remote confirmation failures.
        try { prepared?.let { sheets.confirmDelete(it) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Reconciliation will confirm the prepared intent. */ }
    }
}
