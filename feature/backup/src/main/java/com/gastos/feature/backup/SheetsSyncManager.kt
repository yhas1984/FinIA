package com.gastos.feature.backup

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.ValueRange
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sincroniza en background cada nuevo gasto/ingreso hacia el Google Sheet
 * vinculado. Simplemente añade filas; las fórmulas SUM en la hoja Resumen
 * (establecidas durante la exportación inicial) se recalculan automáticamente.
 */
@Singleton
class SheetsSyncManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SheetsSyncManager"
        private const val PREFS_NAME = "finai_sheets_sync"
        private const val KEY_SHEET_ID = "spreadsheet_id"
        private const val SHEET_GASTOS = "Gastos"
        private const val SHEET_INGRESOS = "Ingresos"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** ¿Hay un sheet vinculado con el que sincronizar? */
    fun isEnabled(): Boolean = getStoredId().isNotBlank()

    fun setSpreadsheetId(id: String) { prefs.edit().putString(KEY_SHEET_ID, id).apply() }
    fun clearSpreadsheetId() { prefs.edit().remove(KEY_SHEET_ID).apply() }

    /** Expone el ID del sheet vinculado. */
    fun getStoredId(): String = prefs.getString(KEY_SHEET_ID, "") ?: ""

    // ---- Sync ----

    /** Añade una fila de gasto. */
    fun syncExpense(invoice: Invoice) = sync(SHEET_GASTOS, listOf(
        df.format(Date(invoice.fecha)), invoice.proveedor,
        invoice.total.toString(), invoice.ivaPercent.toString(),
        invoice.moneda, invoice.nifEmisor ?: "", invoice.notas ?: ""
    ))

    /** Añade una fila de ingreso (tabla incomes). */
    fun syncIncome(income: Income) = sync(SHEET_INGRESOS, listOf(
        df.format(Date(income.fecha)), income.concepto,
        income.monto.toString(), income.moneda,
        income.fuente ?: "", income.notas ?: ""
    ))

    /** Añade una fila de ingreso proveniente de un Invoice INGRESO. */
    fun syncInvoiceIngreso(invoice: Invoice) = sync(SHEET_INGRESOS, listOf(
        df.format(Date(invoice.fecha)), invoice.proveedor,
        invoice.total.toString(), invoice.moneda,
        invoice.nifEmisor ?: "", invoice.notas ?: ""
    ))

    private fun sync(sheet: String, values: List<String>) {
        val sheetId = getStoredId()
        if (sheetId.isBlank()) return
        scope.launch {
            try {
                val sheets = getSheetsService() ?: return@launch
                sheets.spreadsheets().values()
                    .append(sheetId, "$sheet!A:A", ValueRange().setValues(listOf(values)))
                    .setValueInputOption("USER_ENTERED")
                    .setInsertDataOption("INSERT_ROWS")
                    .execute()
            } catch (e: Exception) {
                Log.w(TAG, "Error syncing to $sheet", e)
            }
        }
    }

    private fun getSheetsService(): Sheets? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(SheetsScopes.SPREADSHEETS)
        ).setSelectedAccount(account.account)
        return Sheets.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("FinAI Sync")
            .build()
    }
}
