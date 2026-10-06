package com.gastos.di

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.automation.*
import com.gastos.common.SaveState
import com.gastos.domain.model.DocumentKind
import com.gastos.domain.model.RuleMatch
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class OrganizationEditorSessionTest {
    @Test fun errorRetainsDraftAndDoublePressCannotStartAnotherWrite(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val gate = CompletableDeferred<Unit>()
        val busy = MutableStateFlow(false)
        var writes = 0
        val session = OrganizationEditorSession(SavedStateHandle(), scope, busy,
            persist = { _, _ -> writes++; gate.await(); error("Storage unavailable") }, errorMessage = { it.message.orEmpty() }, onSaved = {})
        val draft = OrganizationDraft(OrganizationEditor.RULE, originalId = "same-rule", merchant = "Test shop", enabled = false)
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                session.open(draft); session.save(Locale.US); session.save(Locale.US)
                session.update(draft.copy(merchant = "Must not change during save")); session.close()
                assertEquals(SaveState.Saving, session.result.value)
                assertEquals(draft, session.editor.value)
                assertEquals(1, writes); assertTrue(busy.value)
            }
            gate.complete(Unit)
            withTimeout(5000) { session.result.first { it is SaveState.Error } }
            assertEquals(draft, session.editor.value); assertFalse(busy.value)
            assertEquals("Storage unavailable", (session.result.value as SaveState.Error).message)
        } finally { scope.cancel() }
    }

    @Test fun successfulWriteClosesOnlyAfterPersistenceAndConsumesSuccessOnce(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val gate = CompletableDeferred<Unit>()
        var confirmations = 0
        val session = OrganizationEditorSession(SavedStateHandle(), scope, MutableStateFlow(false),
            persist = { _, _ -> gate.await() }, errorMessage = { "Error" }, onSaved = { confirmations++ })
        val draft = OrganizationDraft(OrganizationEditor.CATEGORY, name = "Forza")
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                session.open(draft); session.save(Locale.US); session.close()
                assertNotNull(session.editor.value); assertEquals(0, confirmations)
            }
            gate.complete(Unit)
            withTimeout(5000) { session.result.first { it == SaveState.Success } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                session.update(draft.copy(name = "Changed after persistence"))
                assertEquals(draft, session.editor.value)
                session.close(); session.close()
                assertNull(session.editor.value); assertEquals(1, confirmations)
            }
        } finally { scope.cancel() }
    }

    @Test fun recreatedSessionRestoresEveryFieldAndDirtyBaseline() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val handle = SavedStateHandle()
        val original = OrganizationDraft(OrganizationEditor.LIMIT, originalId = "old-limit", kind = DocumentKind.INCOME,
            parentId = "parent", childId = "child", name = "Name", merchant = "Shop", match = RuleMatch.CONTAINS,
            priority = "-2", enabled = false, month = "2026-11", amount = "123,45", currency = "USD", repeat = true, notify = true)
        try {
            val session = OrganizationEditorSession(handle, scope, MutableStateFlow(false), { _, _ -> }, { "Error" }, {})
            session.open(original)
            val edited = original.copy(amount = "150,50", month = "2026-12")
            session.update(edited)
            val restoredHandle = SavedStateHandle(mapOf(
                "organization.editor" to Bundle(requireNotNull(handle.get<Bundle>("organization.editor"))),
                "organization.baseline" to Bundle(requireNotNull(handle.get<Bundle>("organization.baseline")))))
            val recreated = OrganizationEditorSession(restoredHandle, scope, MutableStateFlow(false), { _, _ -> }, { "Error" }, {})
            assertEquals(edited, recreated.editor.value); assertTrue(recreated.hasChanges)
            recreated.update(original); assertFalse(recreated.hasChanges)
            recreated.close(); assertNull(restoredHandle.get<Bundle>("organization.editor"))
        } finally { scope.cancel() }
    }
}
