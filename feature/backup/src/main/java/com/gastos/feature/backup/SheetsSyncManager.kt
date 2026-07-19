package com.gastos.feature.backup

import android.content.Context
import android.content.SharedPreferences
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.Product
import com.gastos.extension.SafeLog
import com.gastos.repository.PremiumStatusProvider
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private val premiumStatus: PremiumStatusProvider
) {
    companion object {
        private const val TAG = "SheetsSyncManager"
        private const val PREFS_NAME = "finai_sheets_sync"
        private const val KEY_SHEET_ID = "spreadsheet_id"
        // Hojas AEAT (España, Orden HAC/773/2019).
        private const val SHEET_RECIBIDAS = "Facturas Recibidas"
        private const val SHEET_NOMINAS = "Nóminas"
        private const val SHEET_PRODUCTOS = "Productos"

        // Letra (A1) de la columna de ID de cada hoja. Es SIEMPRE la
        // última columna con datos, y debe coincidir con las cabeceras
        // que escribe SheetsExportService:
        //   Recibidas: 14 cols (A..N) · Nóminas: 9 (A..I) · Productos: 8 (A..H)
        private const val COL_ID_RECIBIDAS = "N"
        private const val COL_ID_NOMINAS = "I"
        private const val COL_ID_PRODUCTOS = "H"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val df = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** ¿Hay un sheet vinculado con el que sincronizar? */
    fun isEnabled(): Boolean = getStoredId().isNotBlank()

    fun setSpreadsheetId(id: String) { prefs.edit().putString(KEY_SHEET_ID, id).apply() }
    fun clearSpreadsheetId() { prefs.edit().remove(KEY_SHEET_ID).apply() }

    /** Expone el ID del sheet vinculado. */
    fun getStoredId(): String = prefs.getString(KEY_SHEET_ID, "") ?: ""

    // ------------------------------------------------------------------
    // API pública de sync
    // ------------------------------------------------------------------

    /**
     * Alta o edición de un gasto. Requiere [Invoice.id] > 0 (el ID real
     * de Room). Columnas AEAT (mismo orden que [SheetsExportService]):
     *   Nº Factura | Fecha | NIF País | NIF Emisor | Emisor | Base |
     *   IVA % | Cuota | Recargo Eq. | IRPF | Total | Moneda | Notas | ID
     */
    fun upsertExpense(invoice: Invoice) {
        val base = if (invoice.ivaPercent > 0) invoice.total / (1 + invoice.ivaPercent / 100.0) else invoice.total
        val cuota = invoice.total - base
        val numFactura = extractFromOcr(invoice.ocrRawText, "numero_factura")
        upsertRow(
            SHEET_RECIBIDAS, COL_ID_RECIBIDAS, invoice.id,
            listOf(
                numFactura,                               // Nº Factura (extraído del OCR)
                df.format(Date(invoice.fecha)),           // Fecha dd/MM/yyyy
                invoice.paisCodigo,                       // NIF País (ISO)
                invoice.nifEmisor ?: "",                  // NIF Emisor
                invoice.proveedor,                        // Emisor (Razón Social)
                round2(base),                             // Base Imponible
                invoice.ivaPercent,                       // Tipo IVA
                round2(cuota),                            // Cuota IVA
                0.0,                                      // Recargo Eq. (no capturado)
                invoice.irpfPercent,                      // IRPF %
                invoice.total,                            // Total
                invoice.moneda,                           // Moneda
                invoice.notas ?: "",                      // Notas
                invoice.id                                // ID (clave de sync)
            )
        )
    }

    /**
     * Alta o edición de un ingreso/nómina. Requiere [Income.id] > 0.
     * Columnas: Empresa | Fecha | Devengado | Líquido | IRPF % |
     * Base Cot. | Seg. Social | Moneda | ID
     */
    fun upsertIncome(income: Income) {
        upsertRow(
            SHEET_NOMINAS, COL_ID_NOMINAS, income.id,
            listOf(
                income.fuente ?: income.concepto,         // Empresa
                df.format(Date(income.fecha)),            // Fecha
                income.totalDevengado,                    // Devengado
                income.totalNeto,                         // Líquido
                income.irpfPercent,                       // IRPF %
                "",                                       // Base Cot. (no capturada)
                "",                                       // Seg. Social (no capturada)
                income.moneda,                            // Moneda
                income.id                                 // ID (clave de sync)
            )
        )
    }

    /**
     * Añade una fila por cada producto asociado a una factura (gasto).
     * Los productos son append-only: nunca se editan sueltos desde la
     * app; si se borra la factura, [deleteExpense] elimina sus filas.
     * Cada producto debe llevar su [Product.invoiceId] real (> 0).
     * Columnas: Descripción | Cantidad | P.U. | Subtotal | IVA % |
     * Total + IVA | Factura (Proveedor) | InvoiceID
     */
    fun syncProducts(products: List<Product>, proveedor: String) {
        if (products.isEmpty()) return
        products.forEach { p ->
            val totalConIva = p.subtotal * (1 + p.ivaPercent / 100.0)
            appendRow(
                SHEET_PRODUCTOS,
                listOf(
                    p.descripcion, p.cantidad, p.precioUnitario,
                    p.subtotal, p.ivaPercent, round2(totalConIva), proveedor,
                    p.invoiceId
                )
            )
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

    /** Redondea a 2 decimales (base imponible / cuota IVA / total+IVA). */
    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    /**
     * Extrae un campo del JSON crudo guardado en [Invoice.ocrRawText]
     * cuando el AIService escaneó el documento.
     */
    private fun extractFromOcr(ocrRawText: String?, field: String): String {
        if (ocrRawText.isNullOrBlank()) return ""
        return try {
            val jsonMatch = Regex("""\{[\s\S]*\}""").find(ocrRawText)?.value ?: return ""
            val json = org.json.JSONObject(jsonMatch)
            json.optString(field, "")
        } catch (e: Exception) {
            ""
        }
    }

    /** Añade [values] como nueva fila al final de [sheet]. */
    private fun appendRow(sheet: String, values: List<Any>) {
        if (!premiumStatus.isPremium.value) {
            SafeLog.d(TAG, "sync OMITIDO — Sheets es función Premium")
            return
        }
        val sheetId = getStoredId()
        if (sheetId.isBlank()) {
            SafeLog.w(TAG, "sync OMITIDO — sheetId vacío")
            return
        }
        // Los valores contienen importes: solo se loggean en builds de debug.
        SafeLog.d(TAG, "append → hoja='$sheet' valores=$values sheetId=${sheetId.take(8)}…")
        scope.launch {
            try {
                val sheets = getSheetsService()
                if (sheets == null) {
                    SafeLog.w(TAG, "sync OMITIDO — cuenta Google no autenticada")
                    return@launch
                }
                val resp = sheets.spreadsheets().values()
                    .append(sheetId, "'$sheet'!A:A", ValueRange().setValues(listOf(values)))
                    .setValueInputOption("USER_ENTERED")
                    .setInsertDataOption("INSERT_ROWS")
                    .execute()
                SafeLog.d(TAG, "append OK → hoja='$sheet' updRange=${resp.updates?.updatedRange}")
            } catch (e: Exception) {
                SafeLog.e(TAG, "append FALLO hoja='$sheet'", e)
                SafeLog.d(TAG, "append FALLO valores=$values")
            }
        }
    }

    /**
     * Upsert por ID: busca en la columna [keyCol] una fila cuyo valor
     * coincida con [key]; si existe la sobrescribe con [values], si no
     * la añade al final. [keyCol] es también la última columna del
     * rango a escribir (el ID es la última columna en las tres hojas).
     */
    private fun upsertRow(sheet: String, keyCol: String, key: Long, values: List<Any>) {
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
            try {
                val sheets = getSheetsService()
                if (sheets == null) {
                    SafeLog.w(TAG, "sync OMITIDO — cuenta Google no autenticada")
                    return@launch
                }
                val existingRow = findRowsByKey(sheets, sheetId, sheet, keyCol, key).firstOrNull()
                if (existingRow != null) {
                    // Actualización en sitio de la fila existente.
                    sheets.spreadsheets().values()
                        .update(
                            sheetId,
                            "'$sheet'!A$existingRow:$keyCol$existingRow",
                            ValueRange().setValues(listOf(values))
                        )
                        .setValueInputOption("USER_ENTERED")
                        .execute()
                    SafeLog.d(TAG, "upsert UPDATE OK → hoja='$sheet' fila=$existingRow")
                } else {
                    val resp = sheets.spreadsheets().values()
                        .append(sheetId, "'$sheet'!A:A", ValueRange().setValues(listOf(values)))
                        .setValueInputOption("USER_ENTERED")
                        .setInsertDataOption("INSERT_ROWS")
                        .execute()
                    SafeLog.d(TAG, "upsert APPEND OK → hoja='$sheet' updRange=${resp.updates?.updatedRange}")
                }
            } catch (e: Exception) {
                SafeLog.e(TAG, "upsert FALLO hoja='$sheet' id=$key", e)
                SafeLog.d(TAG, "upsert FALLO valores=$values")
            }
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
        val sheetId = getStoredId()
        if (sheetId.isBlank()) {
            SafeLog.w(TAG, "delete OMITIDO — sheetId vacío")
            return
        }
        SafeLog.d(TAG, "delete → hojas=${sheetKeyCols.keys} id=$key sheetId=${sheetId.take(8)}…")
        scope.launch {
            try {
                val sheets = getSheetsService()
                if (sheets == null) {
                    SafeLog.w(TAG, "delete OMITIDO — cuenta Google no autenticada")
                    return@launch
                }

                // Grid IDs numéricos por título (DeleteDimensionRequest los requiere).
                val meta = sheets.spreadsheets().get(sheetId).setIncludeGridData(false).execute()
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
                    findRowsByKey(sheets, sheetId, title, keyCol, key)
                        .sortedDescending() // borrar de abajo arriba
                        .forEach { row ->
                            requests.add(
                                Request().setDeleteDimension(
                                    DeleteDimensionRequest().setRange(
                                        DimensionRange()
                                            .setSheetId(gridId)
                                            .setDimension("ROWS")
                                            .setStartIndex(row - 1) // 0-based, inclusivo
                                            .setEndIndex(row)       // exclusivo
                                    )
                                )
                            )
                        }
                }

                if (requests.isEmpty()) {
                    SafeLog.d(TAG, "delete: sin filas con id=$key (nada que borrar)")
                    return@launch
                }
                sheets.spreadsheets().batchUpdate(
                    sheetId,
                    BatchUpdateSpreadsheetRequest().setRequests(requests)
                ).execute()
                SafeLog.d(TAG, "delete OK → id=$key filas=${requests.size}")
            } catch (e: Exception) {
                SafeLog.e(TAG, "delete FALLO id=$key", e)
            }
        }
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
        return try {
            val resp = sheets.spreadsheets().values()
                .get(spreadsheetId, "'$sheet'!${keyCol}2:$keyCol")
                .execute()
            val values = resp.getValues() ?: return emptyList()
            val rows = mutableListOf<Int>()
            values.forEachIndexed { index, row ->
                val cell = row.firstOrNull()?.toString()?.trim().orEmpty()
                val asNumber = cell.toDoubleOrNull()?.toLong()
                if (cell == key.toString() || (asNumber != null && asNumber == key)) {
                    rows.add(index + 2) // +2: índice 0 ↔ fila 2 (fila 1 = cabecera)
                }
            }
            rows
        } catch (e: Exception) {
            // Hoja inexistente, rango inválido (sheet viejo sin columna
            // de ID) o error de red → se trata como "sin coincidencias":
            // el upsert hará append y el delete no tocará nada.
            SafeLog.w(TAG, "findRowsByKey: fallo leyendo '$sheet': ${e.message}")
            emptyList()
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
