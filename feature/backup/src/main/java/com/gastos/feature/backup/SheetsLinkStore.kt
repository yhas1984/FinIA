@file:Suppress("DEPRECATION")

package com.gastos.feature.backup

import android.content.Context
import android.content.SharedPreferences
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SheetsLinkStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun writerId(): String = synchronized(this) {
        preferences.getString("writer_installation_id", null) ?: java.util.UUID.randomUUID().toString().also {
            check(preferences.edit().putString("writer_installation_id", it).commit()) { "Cannot persist writer identity" }
        }
    }

    fun getSpreadsheetId(account: GoogleSignInAccount): String {
        val id = preferences.getString(getAccountPreferenceKey(account.id, account.email), "").orEmpty()
        if (id.isNotBlank()) preferences.edit().putString("last_account_key", getAccountPreferenceKey(account.id, account.email))
            .putString("last_workbook", id).apply()
        return id
    }

    fun setSpreadsheetId(account: GoogleSignInAccount, spreadsheetId: String) {
        check(preferences.edit()
            .putString(getAccountPreferenceKey(account.id, account.email), spreadsheetId)
            .putString("last_account_key", getAccountPreferenceKey(account.id, account.email))
            .putString("last_workbook", spreadsheetId)
            .remove(creationKey(account))
            .commit()) { "Cannot persist Sheets link" }
    }

    private fun creationKey(account: GoogleSignInAccount): String = "creating:" + getAccountPreferenceKey(account.id, account.email)
    fun pendingCreation(account: GoogleSignInAccount): String? = preferences.getString(creationKey(account), null)
    fun recordCreation(account: GoogleSignInAccount, token: String?) {
        check(preferences.edit().putString(creationKey(account), token).commit()) { "Cannot persist Sheets creation" }
    }

    fun lastDestination(): Pair<String, String>? {
        val account = preferences.getString("last_account_key", null) ?: return null
        val book = preferences.getString("last_workbook", null)?.takeIf(String::isNotBlank) ?: return null
        return account to book
    }

    fun fingerprint(account: String, book: String, uuid: String): String? =
        preferences.getString("synced:$account:$book:$uuid", null)

    fun recordFingerprint(account: String, book: String, uuid: String, fingerprint: String?) {
        val editor = preferences.edit()
        val key = "synced:$account:$book:$uuid"
        if (fingerprint == null) editor.remove(key) else editor.putString(key, fingerprint)
        check(editor.commit()) { "Cannot persist Sheets acknowledgement" }
    }

    fun clearFingerprints(account: String, book: String) {
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith("synced:$account:$book:") }.forEach(editor::remove)
        check(editor.commit()) { "Cannot reset Sheets acknowledgements" }
    }

    fun getLegacySpreadsheetId(): String =
        preferences.getString(LEGACY_SPREADSHEET_ID_KEY, "").orEmpty()

    fun clearLegacySpreadsheetId() {
        preferences.edit().remove(LEGACY_SPREADSHEET_ID_KEY).apply()
    }

    companion object {
        const val PREFERENCES_NAME = "finai_sheets_sync"
        private const val ACCOUNT_SPREADSHEET_ID_PREFIX = "spreadsheet_id_account_"
        private const val LEGACY_SPREADSHEET_ID_KEY = "spreadsheet_id"

        internal fun getAccountPreferenceKey(accountId: String?, email: String?): String {
            val stableAccountId = accountId?.takeIf(String::isNotBlank)
                ?: email?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank)
                ?: error("La cuenta Google no tiene un identificador estable")
            return "$ACCOUNT_SPREADSHEET_ID_PREFIX$stableAccountId"
        }
    }
}
