package com.gastos.automation

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import com.gastos.common.SaveState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/** One recoverable draft and one local write at a time, independent of remote synchronization. */
internal class OrganizationEditorSession(
    private val savedState: SavedStateHandle,
    private val scope: CoroutineScope,
    private val busy: MutableStateFlow<Boolean>,
    private val persist: suspend (OrganizationDraft, Locale) -> Unit,
    private val errorMessage: (Exception) -> String,
    private val onSaved: () -> Unit
) {
    val editor = MutableStateFlow(restore("organization.editor"))
    val result = MutableStateFlow<SaveState>(SaveState.Idle)
    private var baseline: OrganizationDraft? = restore("organization.baseline")
    val hasChanges: Boolean get() = editor.value != baseline
    fun open(draft: OrganizationDraft) {
        if (editor.value != null || busy.value) return
        baseline = draft
        savedState["organization.baseline"] = draft.toBundle()
        update(draft)
    }
    fun update(draft: OrganizationDraft) {
        if (result.value == SaveState.Saving || result.value == SaveState.Success) return
        editor.value = draft
        savedState["organization.editor"] = draft.toBundle()
        result.value = SaveState.Idle
    }
    fun close() {
        if (result.value == SaveState.Saving) return
        if (result.value == SaveState.Success) onSaved()
        editor.value = null
        baseline = null
        savedState["organization.editor"] = null
        savedState["organization.baseline"] = null
        result.value = SaveState.Idle
    }
    fun save(locale: Locale) {
        val draft: OrganizationDraft = editor.value ?: return
        if (busy.value || result.value == SaveState.Saving || result.value == SaveState.Success) return
        busy.value = true
        result.value = SaveState.Saving
        scope.launch {
            try { persist(draft, locale); result.value = SaveState.Success }
            catch (cancelled: CancellationException) { result.value = SaveState.Idle; throw cancelled }
            catch (failure: Exception) { result.value = SaveState.Error(errorMessage(failure)) }
            finally { busy.value = false }
        }
    }
    private fun restore(key: String): OrganizationDraft? = savedState.get<Bundle>(key)?.let(OrganizationDraft::fromBundle)
}
