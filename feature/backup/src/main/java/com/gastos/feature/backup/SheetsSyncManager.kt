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
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sincroniza en background los gastos/ingresos con el Google Sheet
 * vinculado, escribiendo en las hojas AEAT (España):
 *   • Gasto (Invoice GASTO) → "Facturas Recibidas"
 *   • Ingreso / Nómina      → "Nóminas"
 *   • Productos             → "Productos"
 *
 * Sincronización COMPLETA (alta, edición y borrado), no solo append:
 * cada hoja lleva una columna de ID como ÚLTIMA columna ("ID" en
 * Recibidas/Nóminas, "InvoiceID" en Productos), escrita tanto por
 * [SheetsExportService] en la exportación como aquí en cada sync.
 *
 *   • Alta/edición → upsert: si existe fila con ese ID se actualiza
 *     en sitio; si no, se añade al final.
 *   • Borrado de gasto → elimina su fila de Recibidas Y las filas de
 *     sus productos (mismo InvoiceID).
 *   • Borrado de ingreso → elimina su fila de Nóminas.
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
    private val currencyPreference: CurrencyPreference
) {
    companion object {
        private const val TAG = "SheetsSyncManager"
        private const val PREFS_NAME = "finai_sheets_sync"
        private const val KEY_SHEET_ID = "spreadsheet_id"
        private const val KEY_SCHEMA_PREFIX = "schema_v${SheetsSchema.SCHEMA_VERSION}_"
        // Hojas AEAT (España, Orden HAC/773/2019).
        private const val SHEET_RECIBIDAS = SheetsSchema.RECIBIDAS
        private const val SHEET_NOMINAS = SheetsSchema.NOMINAS
        private const val SHEET_PRODUCTOS = SheetsSchema.PRODUCTOS

        // Columnas clave para localizar filas. No tienen por qué ser la
        // última columna escrita: Recibidas añade el enlace Drive en O y
        // Productos el ProductID en I.
        private const val COL_ID_RECIBIDAS = SheetsSchema.RECIBIDAS_KEY_COLUMN
        private const val COL_ID_NOMINAS = SheetsSchema.NOMINAS_KEY_COLUMN
        private const val COL_ID_PRODUCTOS = SheetsSchema.PRODUCTOS_PARENT_COLUMN
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** ¿Hay un sheet vinculado con el que sincronizar? */
    fun isEnabled(): Boolean = getStoredId().isNotBlank()

    fun setSpreadsheetId(id: String) {
        prefs.edit()
            .putString(KEY_SHEET_ID, id)
            .putBoolean(schemaPreferenceKey(id), true)
            .apply()
    }

    fun clearSpreadsheetId() {
        val currentId = getStoredId()
        prefs.edit().remove(KEY_SHEET_ID).remove(schemaPreferenceKey(currentId)).apply()
    }

    /** Expone el ID del sheet vinculado. */
    fun getStoredId(): String = prefs.getString(KEY_SHEET_ID, "") ?: ""

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

    /**
     * Alta o edición de un ingreso/nómina. Requiere [Income.id] > 0.
     * Columnas: Empresa | Fecha | Devengado | Líquido | IRPF % |
     * Base Cot. | Seg. Social | Moneda | ID
     */
    fun upsertIncome(income: Income) {
        upsertRow(
            SHEET_NOMINAS,
            COL_ID_NOMINAS,
            SheetsSchema.NOMINAS_LAST_COLUMN,
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
            !premiumStatus.isPremium.value ||
            getStoredId().isBlank()
        ) return
        val spreadsheetId = getStoredId()
        scope.launch {
            syncMutex.withLock {
                try {
                    val sheets = getSheetsService()
                    if (sheets == null) {
                        SafeLog.w(TAG, "sync OMITIDO — cuenta Google no autenticada")
                        return@withLock
                    }
                    if (ensureSchemaCurrent(spreadsheetId)) return@withLock
                    upsertRowNow(
                        sheets,
                        spreadsheetId,
                        SHEET_RECIBIDAS,
                        COL_ID_RECIBIDAS,
                        SheetsSchema.RECIBIDAS_LAST_COLUMN,
                        invoice.id,
                        expenseValues(invoice)
                    )
                    deleteRowsNow(
                        sheets,
                        spreadsheetId,
                        mapOf(SHEET_PRODUCTOS to COL_ID_PRODUCTOS),
                        invoice.id
                    )
                    products.forEach { product ->
                        appendRowNow(
                            sheets,
                            spreadsheetId,
                            SHEET_PRODUCTOS,
                            SheetsSchema.productRow(
                                product = product,
                                provider = invoice.proveedor,
                                originalCurrency = invoice.moneda,
                                conversion = conversionSnapshot()
                            )
                        )
                    }
                } catch (e: Exception) {
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

    /** Borrado de un ingreso: elimina su fila de "Nóminas". */
    fun deleteIncome(incomeId: Long) {
        deleteRows(mapOf(SHEET_NOMINAS to COL_ID_NOMINAS), incomeId)
    }

    // ------------------------------------------------------------------
    // Mecánica de sync (upsert / append / delete)
    // ------------------------------------------------------------------

    private fun appendRowNow(sheets: Sheets, spreadsheetId: String, sheet: String, values: List<Any>) {
        val response = sheets.spreadsheets().values()
            .append(spreadsheetId, "'$sheet'!A:A", ValueRange().setValues(listOf(values)))
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
        val sheetId = getStoredId()
        if (sheetId.isBlank()) {
            SafeLog.w(TAG, "sync OMITIDO — sheetId vacío")
            return
        }
        SafeLog.d(TAG, "upsert → hoja='$sheet' id=$key valores=$values sheetId=${sheetId.take(8)}…")
        scope.launch {
            syncMutex.withLock {
                try {
                    val sheets = getSheetsService()
                    if (sheets == null) {
                        SafeLog.w(TAG, "sync OMITIDO — cuenta Google no autenticada")
                        return@withLock
                    }
                    if (ensureSchemaCurrent(sheetId)) return@withLock
                    upsertRowNow(sheets, sheetId, sheet, keyCol, lastCol, key, values)
                } catch (e: Exception) {
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
    private suspend fun ensureSchemaCurrent(spreadsheetId: String): Boolean {
        val preferenceKey = schemaPreferenceKey(spreadsheetId)
        if (prefs.getBoolean(preferenceKey, false)) return false

        val account = sheetsExportService.getLastSignedInAccount()
        if (account == null || !sheetsExportService.isSignedIn()) {
            SafeLog.w(TAG, "sync OMITIDO — falta sesión Google para migrar schema v${SheetsSchema.SCHEMA_VERSION}")
            return true
        }

        val invoices = invoiceRepository.getAllInvoices().first()
        val incomes = incomeRepository.getAllIncomes().first()
        val products = productRepository.getAllProducts().first()
        sheetsExportService.exportToSheets(
            account = account,
            invoices = invoices,
            incomes = incomes,
            products = products,
            existingSpreadsheetId = spreadsheetId
        )
        prefs.edit().putBoolean(preferenceKey, true).apply()
        SafeLog.d(TAG, "schema v${SheetsSchema.SCHEMA_VERSION} aplicado mediante reexportación completa")
        return true
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
        val sheetId = getStoredId()
        if (sheetId.isBlank()) {
            SafeLog.w(TAG, "delete OMITIDO — sheetId vacío")
            return
        }
        SafeLog.d(TAG, "delete → hojas=${sheetKeyCols.keys} id=$key sheetId=${sheetId.take(8)}…")
        scope.launch {
            syncMutex.withLock {
                try {
                    val sheets = getSheetsService()
                    if (sheets == null) {
                        SafeLog.w(TAG, "delete OMITIDO — cuenta Google no autenticada")
                        return@withLock
                    }
                    if (ensureSchemaCurrent(sheetId)) return@withLock
                    deleteRowsNow(sheets, sheetId, sheetKeyCols, key)
                } catch (e: Exception) {
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
        val meta = sheets.spreadsheets().get(spreadsheetId).setIncludeGridData(false).execute()
        val gridIdByTitle = meta.sheets.associate {
            (it.properties.title as String) to (it.properties.sheetId as Int)
        }
        val requests = mutableListOf<Request>()
        sheetKeyCols.forEach { (title, keyCol) ->
            val gridId = gridIdByTitle[title]
            if (gridId == null) {
                SafeLog.w(TAG, "delete: hoja '$title' no existe en el sheet")
                return@forEach
            }
            findRowsByKey(sheets, spreadsheetId, title, keyCol, key)
                .sortedDescending()
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
        if (requests.isEmpty()) return
        sheets.spreadsheets().batchUpdate(
            spreadsheetId,
            BatchUpdateSpreadsheetRequest().setRequests(requests)
        ).execute()
        SafeLog.d(TAG, "delete OK → id=$key filas=${requests.size}")
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

    private fun getSheetsService(): Sheets? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(SheetsScopes.SPREADSHEETS)
        ).setSelectedAccount(account.account)
        return Sheets.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("FinAI Sync")
            .build()
    }

    private fun conversionSnapshot(): SheetsSchema.ConversionSnapshot =
        SheetsSchema.ConversionSnapshot(
            targetCurrency = currencyPreference.defaultCurrency.value,
            exchangeRateProvider = exchangeRateProvider
        )

    private fun schemaPreferenceKey(spreadsheetId: String): String = "$KEY_SCHEMA_PREFIX$spreadsheetId"
}
