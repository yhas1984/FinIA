package com.gastos.feature.backup

import android.content.Context
import android.content.SharedPreferences
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class CloudBackupPreferencesTest {
    private fun preferences(values: MutableMap<String, Any?>): SharedPreferences {
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { prefs.getString(any(),any()) } answers { values[firstArg()] as? String ?: secondArg() }
        every { prefs.getLong(any(),any()) } answers { values[firstArg()] as? Long ?: secondArg() }
        every { prefs.getBoolean(any(),any()) } answers { values[firstArg()] as? Boolean ?: secondArg() }
        every { prefs.edit() } returns editor
        every { editor.putString(any(),any()) } answers { values[firstArg()] = secondArg<String?>(); editor }
        every { editor.putLong(any(),any()) } answers { values[firstArg()] = secondArg<Long>(); editor }
        every { editor.putBoolean(any(),any()) } answers { values[firstArg()] = secondArg<Boolean>(); editor }
        every { editor.remove(any()) } answers { values.remove(firstArg()); editor }
        every { editor.apply() } returns Unit
        return prefs
    }

    private fun createStore(values: MutableMap<String, Any?>, links: MutableMap<String, Any?> = mutableMapOf()): CloudBackupPreferences {
        val backupPreferences = preferences(values)
        val linkPreferences = preferences(links)
        return CloudBackupPreferences(mockk<Context> {
            every { getSharedPreferences("finai_cloud_backup", any()) } returns backupPreferences
            every { getSharedPreferences(SheetsLinkStore.PREFERENCES_NAME, any()) } returns linkPreferences
        })
    }

    @Test fun `upgrade preserves daily consent only for the known original account without assigning old dates`() {
        val values = mutableMapOf<String, Any?>("enabled" to true,"last_success" to 999L,"last_error" to "old")
        val links = mutableMapOf<String, Any?>("last_account_key" to "A")
        val store = createStore(values,links)
        store.selectAccount("B")
        assertFalse(store.status().enabled)
        assertNull(store.status().lastSuccessAt)
        assertNull(store.status().lastError)
        store.selectAccount("A")
        assertTrue(store.status().enabled)
        assertNull(store.status().lastSuccessAt)
        assertNull(store.status().lastError)
        assertFalse(store.status().needsConsentConfirmation)
        links["last_account_key"] = "B"
        val restarted = createStore(values,links)
        restarted.selectAccount("B")
        assertFalse(restarted.status().enabled)
    }

    @Test fun `upgrade without a known account asks for opt in rather than assigning legacy consent`() {
        val values = mutableMapOf<String, Any?>("enabled" to true,"last_success" to 999L)
        val store = createStore(values)
        store.selectAccount("B")
        assertFalse(store.status().enabled)
        assertTrue(store.status().needsConsentConfirmation)
        assertNull(store.status().lastSuccessAt)
        store.setEnabled(true)
        assertTrue(store.status().enabled)
        assertFalse(store.status().needsConsentConfirmation)
        store.selectAccount("A")
        assertFalse(store.status().enabled)
    }

    @Test fun `backup date settings and errors are isolated by account and late results do not change active account`() {
        val store = createStore(mutableMapOf())
        store.selectAccount("A")
        store.setEnabled(true)
        store.recordSuccess(123,"A")
        store.selectAccount("B")
        assertFalse(store.status().enabled)
        assertNull(store.status().lastSuccessAt)
        store.recordSuccess(456,"A")
        assertNull(store.status().lastSuccessAt)
        store.recordError("B only")
        store.selectAccount("A")
        assertTrue(store.status().enabled)
        assertEquals(456L,store.status().lastSuccessAt)
        assertNull(store.status().lastError)
        store.selectAccount(null)
        assertNull(store.status().lastSuccessAt)
    }
}
