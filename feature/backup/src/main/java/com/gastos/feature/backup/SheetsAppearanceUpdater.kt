package com.gastos.feature.backup

import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest
import com.google.api.services.sheets.v4.model.Spreadsheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible

internal class SheetsAppearanceUpdater(private val api: Sheets, private val id: String,
    private val backup: (Spreadsheet) -> Unit, private val beforeCommit: suspend () -> Unit) {
    suspend fun apply(locale: SheetsSchema.LocaleCode, force: Boolean = false) {
        val metadata = runInterruptible { api.spreadsheets().get(id).setIncludeGridData(false).execute() }
        val old = SheetsAppearancePlan.ownership(metadata)
        check(old == null || old.version <= SheetsAppearancePlan.VERSION) { "SHEETS_APPEARANCE_NEWER" }
        if (!force && old?.version == SheetsAppearancePlan.VERSION) return
        val source = runInterruptible { api.spreadsheets().get(id).setIncludeGridData(true).setFields(SheetsRecoverySnapshot.FIELDS).execute() }
        val requests = SheetsAppearancePlan.requests(source, locale, force)
        if (requests.isEmpty()) return
        backup(source)
        beforeCommit()
        try {
            runInterruptible { api.spreadsheets().batchUpdate(id, BatchUpdateSpreadsheetRequest().setRequests(requests)).execute() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // The whole batch includes its marker. An applied timeout is success, never another chart insertion.
            val after = try { runInterruptible { api.spreadsheets().get(id).setIncludeGridData(false).execute() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            val actual = after?.developerMetadata.orEmpty().singleOrNull { it.metadataKey == SheetsAppearancePlan.METADATA_KEY }?.metadataValue
            val expected = requests.firstNotNullOfOrNull { it.createDeveloperMetadata?.developerMetadata?.metadataValue ?: it.updateDeveloperMetadata?.developerMetadata?.metadataValue }
            if (actual == null || actual != expected) throw failure
        }
    }
}

data class SheetsAppearanceState(val accountKey: String = "", val bookId: String = "", val applying: Boolean = false, val error: String? = null)
