@file:Suppress("DEPRECATION")

package com.gastos.feature.backup

import android.content.Context
import android.content.SharedPreferences
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.Product
import com.gastos.extension.SafeLog
import com.gastos.repository.CurrencyPreference
import com.gastos.repository.ExchangeRateProvider
import com.gastos.repository.IncomeRepository
import com.gastos.repository.InvoiceRepository
import com.gastos.repository.PremiumStatusProvider
import com.gastos.repository.ProductRepository
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest
import com.google.api.services.sheets.v4.model.DeleteDimensionRequest
import com.google.api.services.sheets.v4.model.DimensionRange
import com.google.api.services.sheets.v4.model.Request
import com.google.api.services.sheets.v4.model.ValueRange
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sincroniza en background los gastos/ingresos con el Google Sheet
 * vinculado, escribiendo en las hojas AEAT (España):
 *   • Gasto (Invoice GASTO) → "Facturas Recibidas"
 *   • Todos los ingresos    → "Ingresos"
 *   • Productos             → "Productos"
 *
 * Sincronización COMPLETA (alta, edición y borrado), no solo append:
 * cada hoja lleva una columna de ID estable ("ID" en
 * Recibidas/Ingresos, "InvoiceID" en Productos), escrita tanto por
 * [SheetsExportService] en la exportación como aquí en cada sync.
 *
 *   • Alta/edición → upsert: si existe fila con ese ID se actualiza
 *     en sitio; si no, se añade al final.
 *   • Borrado de gasto → elimina su fila de Recibidas Y las filas de
 *     sus productos (mismo InvoiceID).
 *   • Borrado de ingreso → elimina su fila de Ingresos.
 *
 * Las fórmulas SUM de la hoja Resumen se recalculan solas al cambiar
 * las filas.
 *
 * Los importes se envían como Double nativos (NO como String con punto
 * decimal) para evitar que Sheets-ES los interprete como fechas.
 *
 * Nota: las filas escritas por versiones antiguas de la app (sin ID)
 * no se pueden casar; una re-exportación completa ("Sincronizar todo"
 * desde Backup) repara el sheet y reescribe todas las filas con ID.
 */
@Singleton
class SheetsSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val premiumStatus: PremiumStatusProvider,
    private val sheetsExportService: SheetsExportService,
    private val invoiceRepository: InvoiceRepository,
    private val incomeRepository: IncomeRepository,
    private val productRepository: ProductRepository,
    private val exchangeRateProvider: ExchangeRateProvider,
    private val currencyPreference: CurrencyPreference,
    private val sheetsLinkStore: SheetsLinkStore,
    private val operationCoordinator: SheetsOperationCoordinator
) {
    companion object {
        private const val TAG = "SheetsSyncManager"
        private const val KEY_SCHEMA_PREFIX = "schema_v${SheetsSchema.SCHEMA_VERSION}_"
        // Hojas AEAT (España, Orden HAC/773/2019).
        private const val SHEET_RECIBIDAS = SheetsSchema.RECIBIDAS
        private const val SHEET_INGRESOS = SheetsSchema.INGRESOS
        private const val SHEET_PRODUCTOS = SheetsSchema.PRODUCTOS

        // Columnas clave para localizar filas. No tienen por qué ser la
        // última columna escrita: Recibidas añade el enlace Drive en O y
        // Productos el ProductID en I.
        private const val COL_ID_RECIBIDAS = SheetsSchema.RECIBIDAS_KEY_COLUMN
        private const val COL_ID_INGRESOS = SheetsSchema.INGRESOS_KEY_COLUMN
        private const val COL_ID_PRODUCTOS = SheetsSchema.PRODUCTOS_PARENT_COLUMN
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = operationCoordinator.mutex
    private val prefs: SharedPreferences =
        context.getSharedPreferences(SheetsLinkStore.PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isEnabled(account: GoogleSignInAccount): Boolean = getStoredId(account).isNotBlank()

    fun setSpreadsheetId(account: GoogleSignInAccount, id: String) {
        sheetsLinkStore.setSpreadsheetId(account, id)
        prefs.edit().putBoolean(schemaPreferenceKey(id), true).apply()
    }

    fun getStoredId(account: GoogleSignInAccount): String = sheetsLinkStore.getSpreadsheetId(account)

    // ------------------------------------------------------------------
    // API pública de sync
    // ------------------------------------------------------------------

    /**
     * Alta o edición de un gasto. Requiere [Invoice.id] > 0 (el ID real
     * de Room). Columnas AEAT (mismo orden que [SheetsExportService]):
     *   Nº Factura | Fecha | NIF País | NIF Emisor | Emisor | Base |
     *   IVA % | Cuota | Recargo Eq. | IRPF | Total | Moneda | Notas |
     *   ID | Foto Drive
     */
    fun upsertExpense(invoice: Invoice) {
        if (invoice.tipo != InvoiceType.GASTO) return
        upsertRow(
            SHEET_RECIBIDAS,
            COL_ID_RECIBIDAS,
            SheetsSchema.RECIBIDAS_LAST_COLUMN,
            invoice.id,
            expenseValues(invoice)
        )
    }

    private fun expenseValues(invoice: Invoice): List<Any> =
        SheetsSchema.expenseRow(invoice, conversionSnapshot())

    /** Alta o edición de cualquier ingreso en la hoja unificada. */
    fun upsertIncome(income: Income) {
        upsertRow(
            SHEET_INGRESOS,
            COL_ID_INGRESOS,
            SheetsSchema.INGRESOS_LAST_COLUMN,
            income.id,
            SheetsSchema.incomeRow(income, conversionSnapshot())
        )
    }

    /**
     * Sincroniza una factura y sustituye sus productos en una sola sección
     * crítica. Así una edición de proveedor o productos no deja filas antiguas
     * ni compite con otros upserts lanzados al mismo tiempo.
     */
    fun syncExpense(invoice: Invoice, products: List<Product>) {
        if (
            invoice.tipo != InvoiceType.GASTO ||
            !premiumStatus.isPremium.value
        ) return
        val link = getActiveLink() ?: return
        scope.launch {
            try {
                if (!isCurrentAccount(link.account)) return@launch
                if (ensureSchemaCurrent(link)) return@launch
                syncMutex.withLock {
                    if (!isCurrentAccount(link.account)) return@withLock
                    val sheets = getSheetsService(link.account)
                    upsertRowNow(
                        sheets,
                        link.spreadsheetId,
                        SHEET_RECIBIDAS,
                        COL_ID_RECIBIDAS,
                        SheetsSchema.RECIBIDAS_LAST_COLUMN,
                        invoice.id,
                        expenseValues(invoice)
                    )
                    val previousProductRows = findRowsByKey(
                        sheets,
                        link.spreadsheetId,
                        SHEET_PRODUCTOS,
                        COL_ID_PRODUCTOS,
                        invoice.id
                    )
                    val productRows = products.map { product ->
                        SheetsSchema.productRow(
                            product = product,
                            provider = invoice.proveedor,
                            originalCurrency = invoice.moneda,
                            conversion = conversionSnapshot()
                        )
                    }
                    appendRowsNow(sheets, link.spreadsheetId, SHEET_PRODUCTOS, productRows)
                    // Añadir primero las nuevas líneas evita borrar las antiguas
                    // si la red falla a mitad de la escritura. Las filas previas
                    // se eliminan por su posición original, sin tocar las nuevas.
                    deleteKnownRowsNow(
                        sheets,
                        link.spreadsheetId,
                        mapOf(SHEET_PRODUCTOS to previousProductRows)
                    )
                    refreshSummaryNow(sheets, link.spreadsheetId)
                }
            } catch (e: Exception) {
                if (!recoverStaleLink(link, e)) {
                    SafeLog.e(TAG, "syncExpense FALLO id=${invoice.id}", e)
                }
            }
        }
    }

    /**
     * Borrado de un gasto: elimina su fila de "Facturas Recibidas" y
     * todas las filas de "Productos" con el mismo InvoiceID.
     */
    fun deleteExpense(invoiceId: Long) {
        deleteRows(
            mapOf(
                SHEET_RECIBIDAS to COL_ID_RECIBIDAS,
                SHEET_PRODUCTOS to COL_ID_PRODUCTOS
            ),
            invoiceId
        )
    }

    /** Borrado de un ingreso en la hoja unificada. */
    fun deleteIncome(incomeId: Long) {
        deleteRows(
            mapOf(SHEET_INGRESOS to COL_ID_INGRESOS),
            incomeId
        )
    }

    // ------------------------------------------------------------------
    // Mecánica de sync (upsert / append / delete)
    // ------------------------------------------------------------------

    private fun appendRowNow(sheets: Sheets, spreadsheetId: String, sheet: String, values: List<Any>) {
        appendRowsNow(sheets, spreadsheetId, sheet, listOf(values))
    }

    private fun appendRowsNow(
        sheets: Sheets,
        spreadsheetId: String,
        sheet: String,
        rows: List<List<Any>>
    ) {
        if (rows.isEmpty()) return
        val response = sheets.spreadsheets().values()
            .append(spreadsheetId, "'$sheet'!A:A", ValueRange().setValues(rows))
            .setValueInputOption("RAW")
            .setInsertDataOption("INSERT_ROWS")
            .execute()
        SafeLog.d(TAG, "append OK → hoja='$sheet' updRange=${response.updates?.updatedRange}")
    }

    /**
     * Upsert por ID: busca en la columna [keyCol] una fila cuyo valor
     * coincida con [key]; si existe la sobrescribe con [values], si no
     * la añade al final. [lastCol] delimita el rango escrito de forma
     * independiente para poder añadir columnas después de la clave.
     */
    private fun upsertRow(
        sheet: String,
        keyCol: String,
        lastCol: String,
        key: Long,
        values: List<Any>
    ) {
        if (!premiumStatus.isPremium.value) {
            SafeLog.d(TAG, "sync OMITIDO — Sheets es función Premium")
            return
        }
        val link = getActiveLink()
        if (link == null) {
            SafeLog.w(TAG, "sync OMITIDO — sheetId vacío")
            return
        }
        SafeLog.d(TAG, "upsert → hoja='$sheet' id=$key valores=$values sheetId=${link.spreadsheetId.take(8)}…")
        scope.launch {
            try {
                if (!isCurrentAccount(link.account)) return@launch
                if (ensureSchemaCurrent(link)) return@launch
                syncMutex.withLock {
                    if (!isCurrentAccount(link.account)) return@withLock
                    val sheets = getSheetsService(link.account)
                    upsertRowNow(sheets, link.spreadsheetId, sheet, keyCol, lastCol, key, values)
                    refreshSummaryNow(sheets, link.spreadsheetId)
                }
            } catch (e: Exception) {
                if (!recoverStaleLink(link, e)) {
                    SafeLog.e(TAG, "upsert FALLO hoja='$sheet' id=$key", e)
                    SafeLog.d(TAG, "upsert FALLO valores=$values")
                }
            }
        }
    }

    private fun upsertRowNow(
        sheets: Sheets,
        spreadsheetId: String,
        sheet: String,
        keyCol: String,
        lastCol: String,
        key: Long,
        values: List<Any>
    ) {
        val existingRow = findRowsByKey(sheets, spreadsheetId, sheet, keyCol, key).firstOrNull()
        if (existingRow != null) {
            sheets.spreadsheets().values()
                .update(
                    spreadsheetId,
                    "'$sheet'!A$existingRow:$lastCol$existingRow",
                    ValueRange().setValues(listOf(values))
                )
                .setValueInputOption("RAW")
                .execute()
            SafeLog.d(TAG, "upsert UPDATE OK → hoja='$sheet' fila=$existingRow")
        } else {
            appendRowNow(sheets, spreadsheetId, sheet, values)
        }
    }

    /** Re-exporta el sheet una vez al detectar un esquema anterior. */
    private suspend fun ensureSchemaCurrent(link: ActiveSheetLink): Boolean {
        val preferenceKey = schemaPreferenceKey(link.spreadsheetId)
        if (prefs.getBoolean(preferenceKey, false)) return false
        return operationCoordinator.migrationMutex.withLock {
            if (prefs.getBoolean(preferenceKey, false)) return@withLock false
            if (!isCurrentAccount(link.account) || !sheetsExportService.isSignedIn()) {
                SafeLog.w(TAG, "sync OMITIDO — falta sesión Google para migrar schema v${SheetsSchema.SCHEMA_VERSION}")
                return@withLock true
            }
            val invoices = invoiceRepository.getAllInvoices().first()
            val incomes = incomeRepository.getAllIncomes().first()
            val products = productRepository.getAllProducts().first()
            sheetsExportService.exportToSheets(
                account = link.account,
                invoices = invoices,
                incomes = incomes,
                products = products,
                existingSpreadsheetId = link.spreadsheetId
            )
            prefs.edit().putBoolean(preferenceKey, true).apply()
            SafeLog.d(TAG, "schema v${SheetsSchema.SCHEMA_VERSION} aplicado mediante reexportación completa")
            true
        }
    }

    /**
     * Elimina TODAS las filas cuyo valor en la columna de ID coincida
     * con [key], en cada una de las hojas indicadas (título → columna
     * de ID). Usa un único batchUpdate con DeleteDimensionRequest en
     * orden descendente de fila para que los índices sigan siendo
     * válidos mientras se aplican.
     */
    private fun deleteRows(sheetKeyCols: Map<String, String>, key: Long) {
        if (!premiumStatus.isPremium.value) {
            SafeLog.d(TAG, "sync OMITIDO — Sheets es función Premium")
            return
        }
        val link = getActiveLink()
        if (link == null) {
            SafeLog.w(TAG, "delete OMITIDO — sheetId vacío")
            return
        }
        SafeLog.d(TAG, "delete → hojas=${sheetKeyCols.keys} id=$key sheetId=${link.spreadsheetId.take(8)}…")
        scope.launch {
            try {
                if (!isCurrentAccount(link.account)) return@launch
                if (ensureSchemaCurrent(link)) return@launch
                syncMutex.withLock {
                    if (!isCurrentAccount(link.account)) return@withLock
                    val sheets = getSheetsService(link.account)
                    deleteRowsNow(sheets, link.spreadsheetId, sheetKeyCols, key)
                    refreshSummaryNow(sheets, link.spreadsheetId)
                }
            } catch (e: Exception) {
                if (!recoverStaleLink(link, e)) {
                    SafeLog.e(TAG, "delete FALLO id=$key", e)
                }
            }
        }
    }

    private fun deleteRowsNow(
        sheets: Sheets,
        spreadsheetId: String,
        sheetKeyCols: Map<String, String>,
        key: Long
    ) {
        val rowsBySheet = sheetKeyCols.mapValues { (title, keyCol) ->
            findRowsByKey(sheets, spreadsheetId, title, keyCol, key)
        }
        val deleted = deleteKnownRowsNow(sheets, spreadsheetId, rowsBySheet)
        if (deleted > 0) SafeLog.d(TAG, "delete OK → id=$key filas=$deleted")
    }

    /** Elimina filas ya resueltas, útil para no incluir filas añadidas después. */
    private fun deleteKnownRowsNow(
        sheets: Sheets,
        spreadsheetId: String,
        rowsBySheet: Map<String, List<Int>>
    ): Int {
        val meta = sheets.spreadsheets().get(spreadsheetId).setIncludeGridData(false).execute()
        val gridIdByTitle = meta.sheets.associate {
            (it.properties.title as String) to (it.properties.sheetId as Int)
        }
        val requests = mutableListOf<Request>()
        rowsBySheet.forEach { (title, rows) ->
            val gridId = gridIdByTitle[title]
            if (gridId == null) {
                SafeLog.w(TAG, "delete: hoja '$title' no existe en el sheet")
                return@forEach
            }
            rows.sortedDescending()
                .forEach { row ->
                    requests.add(
                        Request().setDeleteDimension(
                            DeleteDimensionRequest().setRange(
                                DimensionRange()
                                    .setSheetId(gridId)
                                    .setDimension("ROWS")
                                    .setStartIndex(row - 1)
                                    .setEndIndex(row)
                            )
                        )
                    )
                }
        }
        if (requests.isEmpty()) return 0
        sheets.spreadsheets().batchUpdate(
            spreadsheetId,
            BatchUpdateSpreadsheetRequest().setRequests(requests)
        ).execute()
        return requests.size
    }

    /**
     * Devuelve las filas (1-based) de [sheet] cuya columna [keyCol]
     * contiene el valor [key]. La fila 1 es la cabecera, así que la
     * lectura empieza en la fila 2. Tolera que el ID venga formateado
     * como "5", "5.0" o similar (USER_ENTERED guarda números).
     */
    private fun findRowsByKey(
        sheets: Sheets,
        spreadsheetId: String,
        sheet: String,
        keyCol: String,
        key: Long
    ): List<Int> {
        val response = sheets.spreadsheets().values()
            .get(spreadsheetId, "'$sheet'!${keyCol}2:$keyCol")
            .execute()
        val values = response.getValues() ?: return emptyList()
        val rows = mutableListOf<Int>()
        values.forEachIndexed { index, row ->
            val cell = row.firstOrNull()?.toString()?.trim().orEmpty()
            val asNumber = cell.toDoubleOrNull()?.toLong()
            if (cell == key.toString() || (asNumber != null && asNumber == key)) {
                rows.add(index + 2)
            }
        }
        return rows
    }

    private fun getSheetsService(account: GoogleSignInAccount): Sheets {
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE_FILE)
        ).setSelectedAccount(account.account)
        return Sheets.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("FinAI Sync")
            .build()
    }

    private fun getActiveLink(): ActiveSheetLink? {
        val account = sheetsExportService.getLastSignedInAccount() ?: return null
        if (!sheetsExportService.isSignedIn()) return null
        val spreadsheetId = sheetsLinkStore.getSpreadsheetId(account)
        if (spreadsheetId.isBlank()) return null
        return ActiveSheetLink(account, spreadsheetId)
    }

    private fun isCurrentAccount(account: GoogleSignInAccount): Boolean {
        val currentAccount = sheetsExportService.getLastSignedInAccount() ?: return false
        return SheetsLinkStore.getAccountPreferenceKey(currentAccount.id, currentAccount.email) ==
            SheetsLinkStore.getAccountPreferenceKey(account.id, account.email)
    }

    private suspend fun recoverStaleLink(link: ActiveSheetLink, error: Exception): Boolean {
        if (error !is GoogleJsonResponseException || error.statusCode !in setOf(401, 403, 404)) return false
        if (!isCurrentAccount(link.account)) return true
        return try {
            operationCoordinator.migrationMutex.withLock {
                if (!isCurrentAccount(link.account)) return@withLock true
                val currentId = sheetsLinkStore.getSpreadsheetId(link.account)
                if (currentId.isNotBlank() && currentId != link.spreadsheetId) return@withLock true
                val invoices = invoiceRepository.getAllInvoices().first()
                val incomes = incomeRepository.getAllIncomes().first()
                val products = productRepository.getAllProducts().first()
                val (_, spreadsheetId) = sheetsExportService.exportToSheets(
                    account = link.account,
                    invoices = invoices,
                    incomes = incomes,
                    products = products
                )
                setSpreadsheetId(link.account, spreadsheetId)
                true
            }
        } catch (recoveryError: Exception) {
            SafeLog.e(TAG, "No se pudo recuperar el vínculo de Sheets", recoveryError)
            false
        }
    }

    private suspend fun refreshSummaryNow(sheets: Sheets, spreadsheetId: String) {
        val invoices = invoiceRepository.getAllInvoices().first()
        val incomes = incomeRepository.getAllIncomes().first()
        val conversion = conversionSnapshot()
        val values = SheetsSchema.summaryRows(
            exportDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()),
            reportCurrency = conversion.targetCurrency,
            totals = SheetsSchema.summaryTotals(invoices, incomes, conversion)
        )
        sheets.spreadsheets().values()
            .update(
                spreadsheetId,
                "'${SheetsSchema.RESUMEN}'!A1",
                ValueRange().setValues(values)
            )
            .setValueInputOption("RAW")
            .execute()
    }

    private fun conversionSnapshot(): SheetsSchema.ConversionSnapshot =
        SheetsSchema.ConversionSnapshot(
            targetCurrency = currencyPreference.defaultCurrency.value,
            exchangeRateProvider = exchangeRateProvider
        )

    private fun schemaPreferenceKey(spreadsheetId: String): String = "$KEY_SCHEMA_PREFIX$spreadsheetId"

    private data class ActiveSheetLink(
        val account: GoogleSignInAccount,
        val spreadsheetId: String
    )
}
