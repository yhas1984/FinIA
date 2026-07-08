package com.gastos.feature.backup

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAuthIOException
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.ValueRange
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sincroniza en background cada nuevo gasto/ingreso hacia el Google Sheet
 * vinculado. Si aún no hay sheet vinculado y el usuario está autenticado,
 * intenta crear uno automáticamente la primera vez.
 *
 * Si el usuario aún no hizo sign-in o no aceptó los scopes, el sync queda
 * en estado `NO_SHEET_LINKED`/`NO_ACCOUNT` y se muestra en la UI.
 */
@Singleton
class SheetsSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sheetsExportService: SheetsExportService,
    private val authManager: GoogleAuthManager
) {
    companion object {
        private const val TAG = "SheetsSyncManager"
        private const val PREFS_NAME = "finai_sheets_sync"
        private const val KEY_SHEET_ID = "spreadsheet_id"
        private const val SHEET_GASTOS = "Gastos"
        private const val SHEET_INGRESOS = "Ingresos"
    }

    /**
     * Resultado del último intento de sync. La UI puede observarlo para
     * avisar al usuario de que sus datos no se han sincronizado.
     */
    sealed class SyncResult {
        object Idle : SyncResult()
        object Syncing : SyncResult()
        data class Success(val sheet: String, val at: Long = System.currentTimeMillis()) : SyncResult()
        data class Failure(
            val reason: Reason,
            val sheet: String,
            val message: String? = null,
            val at: Long = System.currentTimeMillis()
        ) : SyncResult() {
            enum class Reason {
                NO_SHEET_LINKED,
                NO_ACCOUNT,
                API_DISABLED,
                FORBIDDEN,
                UNAUTHORIZED,
                NETWORK,
                UNKNOWN
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _status = MutableStateFlow<SyncResult>(SyncResult.Idle)
    val status: StateFlow<SyncResult> = _status.asStateFlow()

    fun isEnabled(): Boolean = getStoredId().isNotBlank()

    fun setSpreadsheetId(id: String) {
        prefs.edit().putString(KEY_SHEET_ID, id).apply()
        Log.d(TAG, "Spreadsheet vinculado: $id")
    }

    fun clearSpreadsheetId() {
        prefs.edit().remove(KEY_SHEET_ID).apply()
        Log.d(TAG, "Spreadsheet desvinculado")
    }

    fun getStoredId(): String = prefs.getString(KEY_SHEET_ID, "") ?: ""

    // ---- Sync ----

    /** Constructor de filas. Una por (sheet, fila). */
    private data class Row(val sheet: String, val values: List<String>, val desc: String)

    fun syncExpense(invoice: Invoice) = enqueueSync(
        Row(SHEET_GASTOS, listOf(
            df.format(Date(invoice.fecha)), invoice.proveedor,
            invoice.baseImponible.toString(), invoice.ivaPercent.toString(),
            invoice.cuotaIva.toString(), invoice.irpfPercent.toString(),
            invoice.cuotaIrpf.toString(), invoice.total.toString(),
            invoice.moneda, invoice.nifEmisor ?: "", invoice.notas ?: ""
        ), "gasto #${invoice.id} ${invoice.proveedor}")
    )

    fun syncIncome(income: Income) = enqueueSync(
        Row(SHEET_INGRESOS, listOf(
            df.format(Date(income.fecha)), income.concepto,
            "Ingreso", income.totalDevengado.toString(),
            income.totalNeto.toString(), income.moneda,
            income.fuente ?: "", income.ivaPercent.toString(),
            income.notas ?: ""
        ), "ingreso #${income.id} ${income.concepto}")
    )

    fun syncInvoiceIngreso(invoice: Invoice) = enqueueSync(
        Row(SHEET_INGRESOS, listOf(
            df.format(Date(invoice.fecha)), invoice.proveedor,
            "Factura emitida", invoice.baseImponible.toString(),
            invoice.total.toString(), invoice.moneda,
            invoice.nifEmisor ?: "", invoice.ivaPercent.toString(),
            invoice.notas ?: ""
        ), "factura-ingreso #${invoice.id} ${invoice.proveedor}")
    )

    /**
     * Resincroniza TODAS las filas conocidas (usado por "Sincronizar"/"Forzar").
     * Si no hay sheet vinculado y el usuario está autenticado, crea el sheet
     * automáticamente primero.
     */
    fun resyncAll(
        invoices: List<Invoice>,
        incomes: List<Income>,
        invoiceIngresos: List<Invoice>
    ) {
        scope.launch {
            val sheetId = ensureSheet(invoices, incomes, invoiceIngresos)
            if (sheetId == null) {
                Log.w(TAG, "resyncAll: cannot ensure sheet")
                return@launch
            }
            var okCount = 0
            var failCount = 0
            invoices.forEach { inv ->
                val r = appendRow(SHEET_GASTOS, listOf(
                    df.format(Date(inv.fecha)), inv.proveedor,
                    inv.baseImponible.toString(), inv.ivaPercent.toString(),
                    inv.cuotaIva.toString(), inv.irpfPercent.toString(),
                    inv.cuotaIrpf.toString(), inv.total.toString(),
                    inv.moneda, inv.nifEmisor ?: "", inv.notas ?: ""
                ))
                if (r) okCount++ else failCount++
            }
            incomes.forEach { inc ->
                val r = appendRow(SHEET_INGRESOS, listOf(
                    df.format(Date(inc.fecha)), inc.concepto,
                    "Ingreso", inc.totalDevengado.toString(),
                    inc.totalNeto.toString(), inc.moneda,
                    inc.fuente ?: "", inc.ivaPercent.toString(),
                    inc.notas ?: ""
                ))
                if (r) okCount++ else failCount++
            }
            invoiceIngresos.forEach { inv ->
                val r = appendRow(SHEET_INGRESOS, listOf(
                    df.format(Date(inv.fecha)), inv.proveedor,
                    "Factura emitida", inv.baseImponible.toString(),
                    inv.total.toString(), inv.moneda,
                    inv.nifEmisor ?: "", inv.ivaPercent.toString(),
                    inv.notas ?: ""
                ))
                if (r) okCount++ else failCount++
            }
            Log.d(TAG, "resyncAll done: ok=$okCount fail=$failCount")
            _status.value = if (failCount == 0) {
                SyncResult.Success(sheet = "Gastos+Ingresos")
            } else {
                SyncResult.Failure(
                    reason = SyncResult.Failure.Reason.UNKNOWN,
                    sheet = "Gastos+Ingresos",
                    message = "Fallaron $failCount filas"
                )
            }
        }
    }

    /**
     * Encola una fila para sincronizar. Si todavía no hay sheet vinculado
     * pero el usuario está autenticado, intenta crear el sheet automáticamente
     * la primera vez. Si no es posible (sin sesión o scopes), marca el
     * estado como Failure y emite LOG con TAG para diagnóstico.
     */
    private fun enqueueSync(row: Row) {
        val sheetId = getStoredId()
        if (sheetId.isBlank()) {
            // No hay sheet vinculado: intentar crear uno automáticamente si
            // el usuario está autenticado y tiene los scopes.
            val account = authManager.getLastSignedInAccount()
            if (account != null && sheetsExportService.isSignedIn()) {
                Log.d(TAG, "No hay sheet vinculado. Creando uno automáticamente para '${row.desc}'…")
                scope.launch {
                    val newId = autoCreateSheet(account)
                    if (newId != null) {
                        Log.d(TAG, "Sheet auto-creado: $newId — procediendo con sync de '${row.desc}'")
                        runSingleSync(row)
                    } else {
                        Log.w(TAG, "No se pudo auto-crear sheet. Sync de '${row.desc}' queda pendiente.")
                        _status.value = SyncResult.Failure(
                            reason = SyncResult.Failure.Reason.NO_SHEET_LINKED,
                            sheet = row.sheet,
                            message = "Ve a Backup y pulsa 'Exportar' para crear el sheet."
                        )
                    }
                }
                return
            }
            // Sin autenticación: marcar para que la UI lo sepa.
            val reason = if (account == null) SyncResult.Failure.Reason.NO_ACCOUNT
                          else SyncResult.Failure.Reason.NO_SHEET_LINKED
            Log.w(TAG, "Skip sync '${row.desc}': ${if (reason == SyncResult.Failure.Reason.NO_ACCOUNT) "NO_ACCOUNT" else "NO_SHEET_LINKED"}")
            _status.value = SyncResult.Failure(reason = reason, sheet = row.sheet)
            return
        }
        _status.value = SyncResult.Syncing
        scope.launch { runSingleSync(row) }
    }

    private suspend fun runSingleSync(row: Row) {
        val maxAttempts = 3
        var attempt = 0
        var delayMs = 1000L
        while (attempt < maxAttempts) {
            attempt++
            val ok = appendRow(row.sheet, row.values)
            if (ok) {
                Log.d(TAG, "✓ Synced '${row.desc}' a ${row.sheet}")
                _status.value = SyncResult.Success(row.sheet)
                return
            }
            if (attempt < maxAttempts) {
                delay(delayMs)
                delayMs *= 2
            }
        }
        Log.w(TAG, "Sync '${row.desc}' FALLÓ tras $maxAttempts intentos")
    }

    /**
     * Crea el spreadsheet vinculado si el usuario ya hizo sign-in y la app
     * tiene los scopes. Devuelve el ID creado o null si no fue posible.
     *
     * Lo expone como `suspend` para que se pueda llamar desde una corrutina.
     * Sólo se crean las hojas con cabecera vacía — los datos se irán
     * sincronizando después con `resyncAll`/`syncExpense`/`syncIncome`.
     */
    suspend fun autoCreateSheetIfNeeded(): String? {
        val existing = getStoredId()
        if (existing.isNotBlank()) return existing
        val account = authManager.getLastSignedInAccount() ?: return null
        if (!sheetsExportService.isSignedIn()) return null
        return autoCreateSheet(account)
    }

    private suspend fun autoCreateSheet(
        account: com.google.android.gms.auth.api.signin.GoogleSignInAccount
    ): String? {
        return try {
            val (url, id) = sheetsExportService.exportToSheets(
                account, emptyList(), emptyList(), emptyList(), ""
            )
            setSpreadsheetId(id)
            Log.d(TAG, "Sheet auto-creado: $url ($id)")
            id
        } catch (e: Exception) {
            Log.w(TAG, "Fallo creando sheet automáticamente", e)
            null
        }
    }

    /** Asegura que hay un sheet vinculado; carga todos los datos si hace falta. */
    private suspend fun ensureSheet(
        invoices: List<Invoice>,
        incomes: List<Income>,
        invoiceIngresos: List<Invoice>
    ): String? {
        val existing = getStoredId()
        if (existing.isNotBlank()) return existing
        val account = authManager.getLastSignedInAccount() ?: run {
            _status.value = SyncResult.Failure(
                reason = SyncResult.Failure.Reason.NO_ACCOUNT,
                sheet = SHEET_GASTOS
            )
            return null
        }
        if (!sheetsExportService.isSignedIn()) {
            _status.value = SyncResult.Failure(
                reason = SyncResult.Failure.Reason.NO_ACCOUNT,
                sheet = SHEET_GASTOS
            )
            return null
        }
        Log.d(TAG, "ensureSheet: creando sheet desde resyncAll…")
        return try {
            val (url, id) = sheetsExportService.exportToSheets(
                account, invoices, incomes, emptyList(), ""
            )
            setSpreadsheetId(id)
            id
        } catch (e: Exception) {
            Log.w(TAG, "ensureSheet fallo", e)
            null
        }
    }

    private suspend fun appendRow(sheet: String, values: List<String>): Boolean {
        val sheetId = getStoredId()
        if (sheetId.isBlank()) return false

        // Hasta 2 intentos: el original + 1 retry tras re-auth silencioso.
        var refreshed = false
        var attempt = 0
        while (attempt < 2) {
            attempt++
            val sheets = getSheetsService()
            if (sheets == null) {
                _status.value = SyncResult.Failure(
                    reason = SyncResult.Failure.Reason.UNAUTHORIZED,
                    sheet = sheet
                )
                return false
            }
            try {
                sheets.spreadsheets().values()
                    .append(sheetId, "$sheet!A1", ValueRange().setValues(listOf(values)))
                    .setValueInputOption("USER_ENTERED")
                    .setInsertDataOption("INSERT_ROWS")
                    .execute()
                return true
            } catch (e: GoogleJsonResponseException) {
                val status = e.statusCode
                val reason = when (status) {
                    401 -> SyncResult.Failure.Reason.UNAUTHORIZED
                    403 -> if ((e.content ?: "").contains("SERVICE_DISABLED", true))
                        SyncResult.Failure.Reason.API_DISABLED
                    else
                        SyncResult.Failure.Reason.FORBIDDEN
                    else -> SyncResult.Failure.Reason.UNKNOWN
                }
                if (status == 401 || status == 403) {
                    Log.w(TAG, "Sync '$sheet' fallo permanente status=$status: ${e.message}")
                    _status.value = SyncResult.Failure(reason = reason, sheet = sheet, message = e.message)
                    return false
                }
                Log.w(TAG, "Sync '$sheet' status=$status (intentando de nuevo)")
                return false
            } catch (e: GoogleAuthIOException) {
                // En 401 el token está caducado o revocado. Intentamos
                // re-auth silencioso y reintentamos una vez.
                Log.w(TAG, "Sync '$sheet' GoogleAuthIOException (401). Re-auth…", e)
                if (refreshed) return false
                refreshed = silentReauthenticate()
                if (!refreshed) {
                    Log.w(TAG, "Re-auth silencioso falló en SheetsSyncManager")
                    _status.value = SyncResult.Failure(
                        reason = SyncResult.Failure.Reason.UNAUTHORIZED,
                        sheet = sheet,
                        message = "Tu sesión de Google requiere atención. Reabre la app."
                    )
                    return false
                }
                Log.d(TAG, "Re-auth OK. Reintentando sync a $sheet")
                // el bucle reintenta con un token nuevo
            } catch (e: IOException) {
                Log.w(TAG, "Sync '$sheet' IO error: ${e.message}")
                _status.value = SyncResult.Failure(
                    reason = SyncResult.Failure.Reason.NETWORK,
                    sheet = sheet,
                    message = e.message
                )
                return false
            } catch (e: Exception) {
                Log.w(TAG, "Sync '$sheet' error: ${e.message}", e)
                _status.value = SyncResult.Failure(
                    reason = SyncResult.Failure.Reason.UNKNOWN,
                    sheet = sheet,
                    message = e.message
                )
                return false
            }
        }
        return false
    }

    private suspend fun getSheetsService(): Sheets? {
        val account = authManager.getLastSignedInAccount() ?: return null
        return try {
            val token = authManager.getAccessToken()
            Sheets.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                BearerTokenInitializer(token)
            )
                .setApplicationName("FinAI Sync")
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "getSheetsService: no se pudo obtener access token", e)
            null
        }
    }

    /**
     * Refresca silenciosamente la sesión Google con los scopes de Sheets.
     * Delegado en [GoogleAuthManager] que mantiene la única instancia de
     * `GoogleSignInClient`.
     */
    private suspend fun silentReauthenticate(): Boolean =
        authManager.silentReauthenticate()
}

