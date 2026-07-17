package com.gastos.feature.backup

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.Product
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
 * vinculado, escribiendo en las hojas AEAT (España):
 *   • Gasto (Invoice GASTO)        → "Facturas Recibidas"
 *   • Nómina (Income de recibo salarial)
 *                                    → "Nóminas"
 *   • Productos                     → "Productos"
 * Las fórmulas SUM en la hoja Resumen (establecidas en el export inicial)
 * se recalculan automáticamente al añadir filas aquí.
 *
 * Los importes se envían como Double nativos (NO como String con punto
 * decimal) para evitar que Sheets-ES los interprete como fechas.
 */
@Singleton
class SheetsSyncManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SheetsSyncManager"
        private const val PREFS_NAME = "finai_sheets_sync"
        private const val KEY_SHEET_ID = "spreadsheet_id"
        // Hojas AEAT (España, Orden HAC/773/2019).
        private const val SHEET_RECIBIDAS = "Facturas Recibidas"
        private const val SHEET_NOMINAS = "Nóminas"
        private const val SHEET_PRODUCTOS = "Productos"
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

    // ---- Sync ----
    //
    // Las columnas de cada fila EXACTAMENTE las mismas que escribe
    // `SheetsExportService` para esa hoja AEAT, en el mismo orden;
    // así append+INSERT_ROWS no desfasea columnas.
    //

    /**
     * Añade una fila a "Facturas Recibidas" (gasto del usuario como
     * receptor). Columnas AEAT:
     *   Nº Factura | Fecha | NIF País | NIF Emisor | Base | IVA % | Cuota |
     *   Recargo Eq. | IRPF | Total | Moneda | Notas
     *
     * Base y cuota se calculan aquí (sin persistirlas en Room).
     */
    fun syncExpense(invoice: Invoice) {
        val base = if (invoice.ivaPercent > 0) invoice.total / (1 + invoice.ivaPercent / 100.0) else invoice.total
        val cuota = invoice.total - base
        val numFactura = extractFromOcr(invoice.ocrRawText, "numero_factura")
        sync(SHEET_RECIBIDAS, listOf(
            numFactura,                                   // Nº Factura (extraído del OCR)
            df.format(Date(invoice.fecha)),                // Fecha dd/MM/yyyy
            invoice.paisCodigo,                            // NIF País (ISO)
            invoice.nifEmisor ?: "",                      // NIF Emisor
            invoice.proveedor,                            // Emisor (Razón Social)
            round2(base),                                  // Base Imponible
            invoice.ivaPercent,                            // Tipo IVA
            round2(cuota),                                 // Cuota IVA
            0.0,                                            // Recargo Eq. (no capturado)
            invoice.irpfPercent,                          // IRPF %
            invoice.total,                                 // Total
            invoice.moneda,                                // Moneda
            invoice.notas ?: ""                           // Notas
        ))
    }

    /**
     * Añade una fila a "Nóminas" (recibo salarial). Columnas:
     *   Empresa | Fecha | Devengado | Líquido | IRPF % | Base Cot. |
     *   Seg. Social | Moneda | Notas
     */
    fun syncIncome(income: Income) {
        sync(SHEET_NOMINAS, listOf(
            income.fuente ?: income.concepto,             // Empresa
            df.format(Date(income.fecha)),                // Fecha
            income.totalDevengado,                         // Devengado
            income.totalNeto,                              // Líquido
            income.irpfPercent,                            // IRPF %
            "",                                            // Base Cot. (no capturada)
            "",                                            // Seg. Social (no capturada)
            income.moneda                                 // Moneda
        ))
    }

    /**
     * Añade una fila por cada producto asociado a una factura (gasto).
     * Columnas: Descripción | Cantidad | P.U. | Subtotal | IVA % |
     * Total + IVA | Factura (Proveedor). Llamar después de [syncExpense].
     */
    fun syncProducts(products: List<Product>, proveedor: String) {
        if (products.isEmpty()) return
        products.forEach { p ->
            val totalConIva = p.subtotal * (1 + p.ivaPercent / 100.0)
            sync(SHEET_PRODUCTOS, listOf(
                p.descripcion, p.cantidad, p.precioUnitario,
                p.subtotal, p.ivaPercent, round2(totalConIva), proveedor
            ))
        }
    }

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

    private fun sync(sheet: String, values: List<Any>) {
        val sheetId = getStoredId()
        if (sheetId.isBlank()) {
            Log.w(TAG, "sync OMITIDO — sheetId vacío")
            return
        }
        Log.d(TAG, "sync → hoja='$sheet' valores=$values sheetId=${sheetId.take(8)}…")
        scope.launch {
            try {
                val sheets = getSheetsService()
                if (sheets == null) {
                    Log.w(TAG, "sync OMITIDO — cuenta Google no autenticada")
                    return@launch
                }
                val resp = sheets.spreadsheets().values()
                    .append(sheetId, "'$sheet'!A:A", ValueRange().setValues(listOf(values)))
                    .setValueInputOption("USER_ENTERED")
                    .setInsertDataOption("INSERT_ROWS")
                    .execute()
                Log.d(TAG, "sync OK → hoja='$sheet' updRange=${resp.updates?.updatedRange}")
            } catch (e: Exception) {
                Log.e(TAG, "sync FALLO hoja='$sheet' valores=$values", e)
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
