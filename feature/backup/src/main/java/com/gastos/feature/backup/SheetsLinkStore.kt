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

    fun getSpreadsheetId(account: GoogleSignInAccount): String {
        return preferences.getString(getAccountPreferenceKey(account.id, account.email), "").orEmpty()
    }

    fun setSpreadsheetId(account: GoogleSignInAccount, spreadsheetId: String) {
        preferences.edit()
            .putString(getAccountPreferenceKey(account.id, account.email), spreadsheetId)
            .apply()
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
