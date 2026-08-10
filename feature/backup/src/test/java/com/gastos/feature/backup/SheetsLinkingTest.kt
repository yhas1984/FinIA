@file:Suppress("DEPRECATION")

package com.gastos.feature.backup

import android.content.Context
import android.content.SharedPreferences
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.util.DateTime
import com.google.api.services.drive.model.File as DriveFile
import com.google.api.services.drive.model.User
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SheetsLinkingTest {

    @Test
    fun `account preference keys are stable and account scoped`() {
        val first = SheetsLinkStore.getAccountPreferenceKey("google-1", "one@example.com")
        val same = SheetsLinkStore.getAccountPreferenceKey("google-1", "other@example.com")
        val second = SheetsLinkStore.getAccountPreferenceKey("google-2", "two@example.com")

        assertEquals(first, same)
        assertNotEquals(first, second)
    }

    @Test
    fun `email fallback is case insensitive`() {
        val first = SheetsLinkStore.getAccountPreferenceKey(null, "User@Example.com")
        val same = SheetsLinkStore.getAccountPreferenceKey(null, "user@example.com")

        assertEquals(first, same)
    }

    @Test
    fun `newest spreadsheet is selected from existing duplicates`() {
        val oldest = DriveFile().setId("old").setCreatedTime(DateTime(1_000L))
        val newest = DriveFile().setId("new").setCreatedTime(DateTime(2_000L))

        assertEquals("new", selectNewestSpreadsheetId(listOf(oldest, newest)))
    }

    @Test
    fun `newer sheet owned by another account is ignored`() {
        val ownSheet = DriveFile()
            .setId("own")
            .setCreatedTime(DateTime(1_000L))
            .setOwners(listOf(User().setEmailAddress("owner@example.com")))
        val sharedSheet = DriveFile()
            .setId("shared")
            .setCreatedTime(DateTime(2_000L))
            .setOwners(listOf(User().setEmailAddress("other@example.com")))

        assertEquals(
            "own",
            selectNewestOwnedSpreadsheetId(listOf(ownSheet, sharedSheet), "OWNER@example.com")
        )
    }

    @Test
    fun `empty spreadsheet discovery has no selection`() {
        assertNull(selectNewestSpreadsheetId(emptyList()))
    }

    @Test
    fun `legacy global link is not exposed to another account`() {
        val values = mutableMapOf("spreadsheet_id" to "legacy-sheet")
        val store = createStore(values)
        val account = mockAccount("account-2", "two@example.com")

        assertEquals("", store.getSpreadsheetId(account))
        assertEquals("legacy-sheet", store.getLegacySpreadsheetId())
    }

    @Test
    fun `spreadsheet links remain isolated by account`() {
        val values = mutableMapOf<String, String>()
        val store = createStore(values)
        val firstAccount = mockAccount("account-1", "one@example.com")
        val secondAccount = mockAccount("account-2", "two@example.com")

        store.setSpreadsheetId(firstAccount, "first-sheet")

        assertEquals("first-sheet", store.getSpreadsheetId(firstAccount))
        assertEquals("", store.getSpreadsheetId(secondAccount))
    }

    private fun mockAccount(id: String, email: String): GoogleSignInAccount =
        mockk<GoogleSignInAccount>().also { account ->
            every { account.id } returns id
            every { account.email } returns email
        }

    private fun createStore(values: MutableMap<String, String>): SheetsLinkStore {
        val context = mockk<Context>()
        val preferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { context.getSharedPreferences(SheetsLinkStore.PREFERENCES_NAME, Context.MODE_PRIVATE) } returns preferences
        every { preferences.getString(any(), any()) } answers { values[firstArg()] ?: secondArg() }
        every { preferences.edit() } returns editor
        every { editor.putString(any(), any()) } answers {
            values[firstArg()] = secondArg()
            editor
        }
        every { editor.remove(any()) } answers {
            values.remove(firstArg())
            editor
        }
        every { editor.apply() } returns Unit
        return SheetsLinkStore(context)
    }
}
