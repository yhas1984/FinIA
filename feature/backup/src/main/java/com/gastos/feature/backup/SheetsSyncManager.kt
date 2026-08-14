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
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest
import com.google.api.services.sheets.v4.model.DeleteDimensionRequest
import com.google.api.services.sheets.v4.model.DimensionRange
import com.google.api.services.sheets.v4.model.Request
import com.google.api.services.sheets.v4.model.ValueRange
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
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
    private val operationCoordinator: SheetsOperationCoordinator,
    private val remoteSyncOutboxRepository: RemoteSyncOutboxRepository,
    private val remoteSyncScheduler: RemoteSyncScheduler
) {
    companion object {
        private const val TAG = "SheetsSyncManager"
        private const val KEY_SCHEMA_PREFIX = "schema_v${SheetsSchema.SCHEMA_VERSION}_"
        // Columnas clave para localizar filas. No tienen por qué ser la
        // última columna escrita: Recibidas añade el enlace Drive en O y
        // Productos el ProductID en I.
        private const val COL_ID_RECIBIDAS = SheetsSchema.RECIBIDAS_KEY_COLUMN
        private const val COL_ID_INGRESOS = SheetsSchema.INGRESOS_KEY_COLUMN
        private const val COL_ID_PRODUCTOS = SheetsSchema.PRODUCTOS_PARENT_COLUMN
    }

    private val syncMutex = operationCoordinator.mutex
    private val prefs: SharedPreferences =
        context.getSharedPreferences(SheetsLinkStore.PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isEnabled(account: GoogleSignInAccount): Boolean = getStoredId(account).isNotBlank()

    fun setSpreadsheetId(account: GoogleSignInAccount, id: String) {
        sheetsLinkStore.setSpreadsheetId(account, id)
        remoteSyncScheduler.schedule()
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
    suspend fun upsertExpense(invoice: Invoice) {
        if (invoice.tipo != InvoiceType.GASTO) return
        remoteSyncOutboxRepository.enqueue(RemoteSyncTarget.EXPENSE_SHEETS, invoice.id, RemoteSyncAction.UPSERT)
    }

    private fun expenseValues(invoice: Invoice, locale: SheetsSchema.LocaleCode): List<Any> =
        SheetsSchema.expenseRow(invoice, conversionSnapshot(locale))

    /** Alta o edición de cualquier ingreso en la hoja unificada. */
    suspend fun upsertIncome(income: Income) {
        remoteSyncOutboxRepository.enqueue(RemoteSyncTarget.INCOME_SHEETS, income.id, RemoteSyncAction.UPSERT)
    }

    /**
     * Sincroniza una factura y sustituye sus productos en una sola sección
     * crítica. Así una edición de proveedor o productos no deja filas antiguas
     * ni compite con otros upserts lanzados al mismo tiempo.
     */
    suspend fun syncExpense(invoice: Invoice, products: List<Product>) {
        if (invoice.tipo != InvoiceType.GASTO) return
        remoteSyncOutboxRepository.enqueue(RemoteSyncTarget.EXPENSE_SHEETS, invoice.id, RemoteSyncAction.UPSERT)
    }

    /**
     * Borrado de un gasto: elimina su fila de "Facturas Recibidas" y
     * todas las filas de "Productos" con el mismo InvoiceID.
     */
    suspend fun deleteExpense(invoiceId: Long) {
        remoteSyncOutboxRepository.enqueue(RemoteSyncTarget.EXPENSE_SHEETS, invoiceId, RemoteSyncAction.DELETE)
    }

    /** Borrado de un ingreso en la hoja unificada. */
    suspend fun deleteIncome(incomeId: Long) {
        remoteSyncOutboxRepository.enqueue(RemoteSyncTarget.INCOME_SHEETS, incomeId, RemoteSyncAction.DELETE)
    }

    suspend fun performExpenseSync(invoiceId: Long): Boolean {
        val invoice = invoiceRepository.getInvoiceById(invoiceId) ?: return true
        if (invoice.tipo != InvoiceType.GASTO) return true
        val products = productRepository.getProductsByInvoiceId(invoiceId).firstOrNull().orEmpty()
        val link = getActiveLink() ?: return false
        if (!isCurrentAccount(link.account) || ensureSchemaCurrent(link)) return false
        syncMutex.withLock {
            val locale = resolveLocale(link)
            val descriptor = SheetsSchema.descriptor(locale)
            val sheets = getSheetsService(link.account)
            upsertRowNow(sheets, link.spreadsheetId, descriptor.recibidasTitle, COL_ID_RECIBIDAS, SheetsSchema.RECIBIDAS_LAST_COLUMN, invoice.id, expenseValues(invoice, locale))
            val previousProductRows = findRowsByKey(sheets, link.spreadsheetId, descriptor.productosTitle, COL_ID_PRODUCTOS, invoice.id)
            val productRows = products.map { product -> SheetsSchema.productRow(product, invoice.proveedor, invoice.moneda, conversionSnapshot(locale)) }
            appendRowsNow(sheets, link.spreadsheetId, descriptor.productosTitle, productRows)
            deleteKnownRowsNow(sheets, link.spreadsheetId, mapOf(descriptor.productosTitle to previousProductRows))
            refreshSummaryNow(sheets, link.spreadsheetId)
        }
        return true
    }

    suspend fun performExpenseDelete(invoiceId: Long): Boolean = deleteRowsDirect(invoiceId, expense = true)

    suspend fun performIncomeUpsert(incomeId: Long): Boolean {
        val income = incomeRepository.getIncomeById(incomeId) ?: return true
        val link = getActiveLink() ?: return false
        if (!isCurrentAccount(link.account) || ensureSchemaCurrent(link)) return false
        syncMutex.withLock {
            val locale = resolveLocale(link)
            val descriptor = SheetsSchema.descriptor(locale)
            val sheets = getSheetsService(link.account)
            upsertRowNow(
                sheets,
                link.spreadsheetId,
                descriptor.ingresosTitle,
                COL_ID_INGRESOS,
                SheetsSchema.INGRESOS_LAST_COLUMN,
                income.id,
                SheetsSchema.incomeRow(income, conversionSnapshot(locale))
            )
            refreshSummaryNow(sheets, link.spreadsheetId)
        }
        return true
    }

    suspend fun performIncomeDelete(incomeId: Long): Boolean = deleteRowsDirect(incomeId, expense = false)

    suspend fun performExpenseDeleteRemote(invoiceId: Long): Boolean = performExpenseDelete(invoiceId)

    suspend fun performIncomeDeleteRemote(incomeId: Long): Boolean = performIncomeDelete(incomeId)

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
        return operationCoordinator.migrationMutex.withLock {
            val remote = readRemoteSchemaState(link.account, link.spreadsheetId)
            if (remote?.isCurrent == true) {
                remote.locale?.let { prefs.edit().putString(schemaLocaleKey(link.spreadsheetId), it.code).apply() }
                return@withLock false
            }
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
            SafeLog.d(TAG, "schema v${SheetsSchema.SCHEMA_VERSION} aplicado mediante reexportación completa")
            false
        }
    }

    /**
     * Elimina TODAS las filas cuyo valor en la columna de ID coincida
     * con [key], en cada una de las hojas indicadas (título → columna
     * de ID). Usa un único batchUpdate con DeleteDimensionRequest en
     * orden descendente de fila para que los índices sigan siendo
     * válidos mientras se aplican.
     */
    private suspend fun deleteRowsDirect(key: Long, expense: Boolean): Boolean {
        if (!premiumStatus.isPremium.value) {
            SafeLog.d(TAG, "sync OMITIDO — Sheets es función Premium")
            return false
        }
        val link = getActiveLink()
        if (link == null) {
            SafeLog.w(TAG, "delete OMITIDO — sheetId vacío")
            return false
        }
        SafeLog.d(TAG, "delete → id=$key sheetId=${link.spreadsheetId.take(8)}…")
        if (!isCurrentAccount(link.account)) return false
        if (ensureSchemaCurrent(link)) return false
        syncMutex.withLock {
            if (!isCurrentAccount(link.account)) return false
            val sheetKeyCols = resolveSheetKeyCols(resolveLocale(link), expense)
            val sheets = getSheetsService(link.account)
            deleteRowsNow(sheets, link.spreadsheetId, sheetKeyCols, key)
            refreshSummaryNow(sheets, link.spreadsheetId)
        }
        return true
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
            false
            }
        } catch (recoveryError: Exception) {
            SafeLog.e(TAG, "No se pudo recuperar el vínculo de Sheets", recoveryError)
            false
        }
    }

    private suspend fun refreshSummaryNow(sheets: Sheets, spreadsheetId: String) {
        val invoices = invoiceRepository.getAllInvoices().first()
        val incomes = incomeRepository.getAllIncomes().first()
        val account = sheetsExportService.getLastSignedInAccount() ?: return
        val locale = readRemoteSchemaState(account, spreadsheetId)?.locale ?: SheetsSchema.LocaleCode.ES
        val conversion = conversionSnapshot(locale)
        val descriptor = SheetsSchema.descriptor(locale)
        val values = SheetsSchema.summaryRows(
            descriptor = descriptor,
            exportDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()),
            reportCurrency = conversion.targetCurrency,
            totals = SheetsSchema.summaryTotals(invoices, incomes, conversion)
        )
        sheets.spreadsheets().values()
            .update(
                spreadsheetId,
                "'${descriptor.resumenTitle}'!A1",
                ValueRange().setValues(values)
            )
            .setValueInputOption("RAW")
            .execute()
    }

    private fun conversionSnapshot(locale: SheetsSchema.LocaleCode): SheetsSchema.ConversionSnapshot =
        SheetsSchema.ConversionSnapshot(
            targetCurrency = currencyPreference.defaultCurrency.value,
            locale = locale,
            exchangeRateProvider = exchangeRateProvider
        )

    private fun resolveLocale(link: ActiveSheetLink): SheetsSchema.LocaleCode =
        readRemoteSchemaState(link.account, link.spreadsheetId)?.locale ?: SheetsSchema.LocaleCode.ES

    private fun resolveSheetKeyCols(locale: SheetsSchema.LocaleCode, expense: Boolean): Map<String, String> {
        val descriptor = SheetsSchema.descriptor(locale)
        return if (expense) {
            mapOf(descriptor.recibidasTitle to COL_ID_RECIBIDAS, descriptor.productosTitle to COL_ID_PRODUCTOS)
        } else {
            mapOf(descriptor.ingresosTitle to COL_ID_INGRESOS)
        }
    }

    private fun schemaLocaleKey(spreadsheetId: String): String = "$KEY_SCHEMA_PREFIX${spreadsheetId}_locale"

    private data class RemoteSchemaState(val isCurrent: Boolean, val locale: SheetsSchema.LocaleCode?)

    private fun readRemoteSchemaState(account: GoogleSignInAccount, spreadsheetId: String): RemoteSchemaState? {
        return try {
            val credential = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE_FILE)).setSelectedAccount(account.account)
            val drive = Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential).setApplicationName("FinAI Sync").build()
            val file = drive.files().get(spreadsheetId).setFields("appProperties").execute()
            val appProps = file.appProperties.orEmpty()
            val rawLocale = appProps["finaiSchemaLocale"]
            val locale = rawLocale
                ?.takeIf { it == SheetsSchema.SCHEMA_LOCALE_ES || it == SheetsSchema.SCHEMA_LOCALE_EN }
                ?.let(SheetsSchema::localeFromCode)
            val version = appProps["finaiSchemaVersion"]?.toIntOrNull()
            val isCurrent = appProps["finaiSpreadsheet"] == "true" &&
                version == SheetsSchema.SCHEMA_VERSION &&
                locale != null
            RemoteSchemaState(isCurrent = isCurrent, locale = locale)
        } catch (_: Exception) {
            null
        }
    }

    private data class ActiveSheetLink(
        val account: GoogleSignInAccount,
        val spreadsheetId: String
    )
}
