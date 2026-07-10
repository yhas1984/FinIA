package com.gastos.feature.backup

import android.content.Context
import android.content.Intent
import android.util.Log
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.Product
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.AddSheetRequest
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest
import com.google.api.services.sheets.v4.model.CellFormat
import com.google.api.services.sheets.v4.model.Color
import com.google.api.services.sheets.v4.model.GridProperties
import com.google.api.services.sheets.v4.model.NumberFormat
import com.google.api.services.sheets.v4.model.RepeatCellRequest
import com.google.api.services.sheets.v4.model.Request
import com.google.api.services.sheets.v4.model.SheetProperties
import com.google.api.services.sheets.v4.model.Spreadsheet
import com.google.api.services.sheets.v4.model.SpreadsheetProperties
import com.google.api.services.sheets.v4.model.TextFormat
import com.google.api.services.sheets.v4.model.UpdateCellsRequest
import com.google.api.services.sheets.v4.model.ValueRange
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exporta los datos de FinAI a un Google Sheet con estructura AEAT
 * (Orden HAC/773/2019) para España:
 *   • Facturas Recibidas (gastos del usuario como receptor)
 *   • Nóminas            (recibos salariales)
 *   • Productos          (con Total + IVA)
 *   • Resumen            (fórmulas SUM que recalculan al sincronizar)
 *
 * Usa la cuenta Google autenticada del dispositivo (OAuth) vía
 * GoogleSignIn.  Base imponible y cuota IVA se CALCULAN en el export
 * (base = total/(1+iva%)), sin necesidad de migrar el esquema de Room.
 */
@Singleton
class SheetsExportService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // Scopes necesarios: crear/editar Sheets y archivos en Drive.
        private val SHEETS_SCOPE = Scope(SheetsScopes.SPREADSHEETS)
        private val DRIVE_FILE_SCOPE = Scope("https://www.googleapis.com/auth/drive.file")
    }

    /** Cliente de Google Sign-In con los scopes de Sheets. */
    fun getSignInClient(): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(SHEETS_SCOPE, DRIVE_FILE_SCOPE)
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    /** Intent para lanzar el flujo de Sign-In con permisos de Sheets. */
    fun getSignInIntent(): Intent = getSignInClient().signInIntent

    /** ¿Hay una cuenta Google con permisos de Sheets concedidos? */
    fun isSignedIn(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return account != null &&
            GoogleSignIn.hasPermissions(account, SHEETS_SCOPE, DRIVE_FILE_SCOPE)
    }

    fun getSignedInEmail(): String? =
        GoogleSignIn.getLastSignedInAccount(context)?.email

    /** Devuelve la cuenta autenticada, o null si no hay sesión con permisos. */
    fun getLastSignedInAccount(): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)

    /**
     * Crea un spreadsheet nuevo o, si [existingSpreadsheetId] no está vacío,
     * sobreescribe las hojas del spreadsheet existente con los datos actuales.
     */
    suspend fun exportToSheets(
        account: GoogleSignInAccount,
        invoices: List<Invoice>,
        incomes: List<Income>,
        products: List<Product>,
        existingSpreadsheetId: String = ""
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        // Credential OAuth a partir de la cuenta autenticada.
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(SheetsScopes.SPREADSHEETS, "https://www.googleapis.com/auth/drive.file")
        ).setSelectedAccount(account.account)

        val sheetsService = Sheets.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("FinAI")
            .build()

        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

        val spreadsheetId: String
        val sheetTitles = listOf("Facturas Recibidas", "Nóminas", "Productos", "Resumen")
        if (existingSpreadsheetId.isNotBlank()) {
            // Reutilizar spreadsheet existente — limpiar hojas y reescribir.
            spreadsheetId = existingSpreadsheetId
            clearSheets(sheetsService, spreadsheetId, sheetTitles)
            // Crear las hojas AEAT que falten en el sheet viejo (que
            // puede tener "Gastos/Ingresos" de versiones anteriores).
            ensureSheetsExist(sheetsService, spreadsheetId, sheetTitles)
        } else {
            // Crear spreadsheet nuevo con la primera hoja.
            val spreadsheet = Spreadsheet()
                .setProperties(
                    SpreadsheetProperties().setTitle("FinAI - Exportación $dateStr")
                )
                .setSheets(
                    listOf(
                        com.google.api.services.sheets.v4.model.Sheet()
                            .setProperties(SheetProperties().setTitle(sheetTitles.first()))
                    )
                )
            val created: Spreadsheet = sheetsService.spreadsheets().create(spreadsheet).execute()
            spreadsheetId = created.spreadsheetId

            // Añadir las hojas restantes.
            ensureSheetsExist(sheetsService, spreadsheetId, sheetTitles)
        }

        // Poblar las hojas (estructura AEAT para España).
        //   • Facturas Recibidas  ← Invoice con tipo=GASTO (gastos del usuario)
        //   • Nóminas             ← Income (todos los ingresos)
        // Los Invoice con tipo=INGRESO (factura_emitida) no se exportan:
        // el usuario no genera facturas expedidas.
        val recibidas = invoices.filter { it.tipo == InvoiceType.GASTO }
        writeRecibidas(sheetsService, spreadsheetId, recibidas)
        writeNominas(sheetsService, spreadsheetId, incomes)
        writeProductos(sheetsService, spreadsheetId, products, invoices)
        writeResumenAeat(sheetsService, spreadsheetId, dateStr)

        // Formatear cabeceras (negrita) en todas las hojas.
        formatHeaders(sheetsService, spreadsheetId, sheetTitles)

        val url = "https://docs.google.com/spreadsheets/d/$spreadsheetId/edit"
        Pair(url, spreadsheetId)
    }

    /**
     * Escribe la hoja "Facturas Recibidas" (gastos del usuario como
     * receptor) con columnas AEAT: Nº Factura, Fecha (dd/mm/yyyy),
     * NIF País (ISO), NIF Emisor, Base Imponible, Tipo IVA, Cuota IVA,
     * Recargo Eq., IRPF, Total.
     *
     * Base / cuota se CALCULAN aquí en el export (sin migration de
     * Room): base = total / (1 + iva%/100); cuota = total - base.
     */
    private fun writeRecibidas(sheets: Sheets, id: String, recibidas: List<Invoice>) {
        val values = mutableListOf<List<Any>>(
            listOf(
                "Nº Factura", "Fecha", "NIF País", "NIF Emisor",
                "Emisor (Razón Social)",
                "Base Imponible", "Tipo IVA", "Cuota IVA",
                "Recargo Eq.", "IRPF", "Total", "Moneda", "Notas"
            )
        )
        val df = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        recibidas.forEach { inv ->
            val base = if (inv.ivaPercent > 0) inv.total / (1 + inv.ivaPercent / 100.0) else inv.total
            val cuota = inv.total - base
            // Nº Factura: se extrae del JSON crudo guardado en ocrRawText
            // por el AIService cuando se escaneó la factura. Sin Room
            // migration: número viviente en ocrRawText.
            val numFactura = extractFromOcr(inv.ocrRawText, "numero_factura")
                .ifBlank { "" }
            values.add(
                listOf(
                    numFactura,
                    df.format(Date(inv.fecha)),
                    inv.paisCodigo,
                    inv.nifEmisor ?: "",
                    inv.proveedor,  // Emisor (Razón Social)
                    round2(base),
                    inv.ivaPercent,
                    round2(cuota),
                    0.0,  // Recargo Eq.: no se captura aún
                    inv.irpfPercent,
                    inv.total,
                    inv.moneda,
                    inv.notas ?: ""
                )
            )
        }
        sheets.spreadsheets().values()
            .update(id, "'Facturas Recibidas'!A1", ValueRange().setValues(values))
            .setValueInputOption("USER_ENTERED")
            .execute()
    }

    /**
     * Extrae un campo del JSON crudo guardado en [Invoice.ocrRawText] /
     * parte de [Income.notas] cuando el AIService escaneó el documento.
     * Devuelve "" si ocrRawText no es JSON válido o el campo no existe.
     */
    private fun extractFromOcr(ocrRawText: String?, field: String): String {
        if (ocrRawText.isNullOrBlank()) return ""
        return try {
            val jsonMatch = Regex("""\{[\s\S]*\}""").find(ocrRawText)?.value ?: return ""
            val json = org.json.JSONObject(jsonMatch)
            json.optString(field, "").ifBlank { "" }
        } catch (e: Exception) {
            ""
        }
    }

    // (Facturas Expedidas retirada — el usuario no genera facturas emitidas)

    /**
     * Escribe la hoja "Nóminas" con: Empresa, Fecha, Devengado, Líquido,
     * IRPF %, Base Cot., Seg. Social, Moneda, Notas.
     */
    private fun writeNominas(sheets: Sheets, id: String, nominas: List<Income>) {
        val values = mutableListOf<List<Any>>(
            listOf(
                "Empresa", "Fecha", "Devengado", "Líquido",
                "IRPF %", "Base Cot.", "Seg. Social", "Moneda"
            )
        )
        val df = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        nominas.forEach { inc ->
            values.add(
                listOf(
                    inc.fuente ?: inc.concepto,
                    df.format(Date(inc.fecha)),
                    inc.totalDevengado,
                    inc.totalNeto,
                    inc.irpfPercent,
                    "",   // base cotización no capturada
                    "",   // Seguridad Social no capturada
                    inc.moneda
                )
            )
        }
        sheets.spreadsheets().values()
            .update(id, "Nóminas!A1", ValueRange().setValues(values))
            .setValueInputOption("USER_ENTERED")
            .execute()
    }

    /**
     * Hoja "Resumen" con fórmulas SUM que suman las columnas de Total
     * de Recibidas, Expedidas y Nóminas. Se recalculan automáticamente al
     * añadir filas vía sync.
     */
    private fun writeResumenAeat(sheets: Sheets, id: String, exportDate: String) {
        val values = listOf(
            listOf("Resumen Financiero (AEAT)"),
            listOf("Fecha exportación", exportDate),
            // 'Facturas Recibidas'!K = Total
            listOf("Total Gastos (Recibidas)", "=SUM('Facturas Recibidas'!K2:K)"),
            // 'Nóminas'!D = Líquido
            listOf("Total Nóminas (Líquido)", "=SUM('Nóminas'!D2:D)"),
            listOf("Balance", "=B4-B3")
        )
        sheets.spreadsheets().values()
            .update(id, "Resumen!A1", ValueRange().setValues(values))
            .setValueInputOption("USER_ENTERED")
            .execute()
    }

    private fun writeProductos(
        sheets: Sheets,
        id: String,
        products: List<Product>,
        invoices: List<Invoice>
    ) {
        val values = mutableListOf<List<Any>>(
            listOf("Descripción", "Cantidad", "Precio Unitario", "Subtotal",
                   "IVA %", "Total + IVA", "Factura (Proveedor)")
        )
        // Mapa idFactura -> proveedor para enriquecer.
        val invMap = invoices.associate { it.id to it.proveedor }
        products.forEach { p ->
            val totalConIva = p.subtotal * (1 + p.ivaPercent / 100.0)
            values.add(
                listOf(
                    p.descripcion,
                    p.cantidad,
                    p.precioUnitario,
                    p.subtotal,
                    p.ivaPercent,
                    round2(totalConIva),
                    invMap[p.invoiceId] ?: ""
                )
            )
        }
        sheets.spreadsheets().values()
            .update(id, "Productos!A1", ValueRange().setValues(values))
            .setValueInputOption("USER_ENTERED")
            .execute()
    }

    /** Redondea a 2 decimales (para base imponible / cuota IVA). */
    private fun round2(v: Double): Double =
        Math.round(v * 100.0) / 100.0

    /**
     * Limpia el contenido de las hojas indicadas y, además, resetea los
     * formatos de celda heredados (p.ej. una columna C con formato DATE
     * proveniente de un sheet viejo). Sin esta limpieza, `USER_ENTERED`
     * puede interpretar "18.03" como fecha serial 46099 (hoy) al caer en
     * una celda previamente formateada como fecha.
     */
    private fun clearSheets(sheets: Sheets, id: String, titles: List<String>) {
        // 1) Limpiar valores en A2:Z (no toca cabecera).
        //    IMPORTANTE: los nombres de hoja con espacios o acentos
        //    ("Facturas Recibidas", "Nóminas") requieren comillas
        //    simples en A1 notation, si no la API devuelve 400
        //    "Unable to parse range". Si la hoja no existe (reexport a
        //    un sheet viejo con "Gastos/Ingresos"), se ignora el error
        //    y seguimos — la reapertura más abajo la crea.
        titles.forEach { title ->
            try {
                sheets.spreadsheets().values().clear(id, "'$title'!A2:Z", null).execute()
            } catch (e: Exception) {
                Log.w("SheetsExport", "clearSheets: hoja '$title' no existe aún, se omite")
            }
        }

        // 2) Resetear formatos en TODO el rango A1:Z1000 de cada hoja para
        //    borrar cualquier formato de celda heredado/obsoleto. Se usa
        //    updateCells con fields=userEnteredFormat y sin filas → limpia
        //    el formato de todas las celdas del rango.
        val meta = sheets.spreadsheets().get(id).setIncludeGridData(false).execute()
        val sheetIdByTitle = meta.sheets.associate {
            (it.properties.title as String) to (it.properties.sheetId as Int)
        }
        val requests = titles.mapNotNull { title ->
            val sheetId = sheetIdByTitle[title] ?: return@mapNotNull null
            Request().setRepeatCell(
                RepeatCellRequest()
                    .setFields("userEnteredFormat")
                    .setRange(
                        com.google.api.services.sheets.v4.model.GridRange()
                            .setSheetId(sheetId)
                            .setStartRowIndex(0)
                            .setEndRowIndex(1000)
                            .setStartColumnIndex(0)
                            .setEndColumnIndex(26)
                    )
                    .setCell(com.google.api.services.sheets.v4.model.CellData())
            )
        }
        if (requests.isNotEmpty()) {
            sheets.spreadsheets().batchUpdate(
                id,
                BatchUpdateSpreadsheetRequest().setRequests(requests)
            ).execute()
        }
    }

    /**
     * Garantiza que existan las hojas AEAT en el spreadsheet. Crea vía
     * batchUpdate (AddSheet) las que falten. Idempotente: si todas
     * existen, no hace nada. Esencial al reexportar sobre un sheet
     * viejo con "Gastos/Ingresos" (de versiones anteriores de la app),
     * porque [writeRecibidas]/[writeExpedidas]/[writeNominas] harían
     * 400 "Unable to parse range" si la hoja destino no existe.
     */
    private fun ensureSheetsExist(sheets: Sheets, id: String, titles: List<String>) {
        val meta = sheets.spreadsheets().get(id).setIncludeGridData(false).execute()
        val existing = meta.sheets.map { it.properties.title as String }.toSet()
        val missing = titles.filter { it !in existing }
        if (missing.isEmpty()) return
        Log.d("SheetsExport", "ensureSheetsExist: creando hojas faltantes=$missing")
        val addReqs = missing.map { title ->
            Request().setAddSheet(
                AddSheetRequest().setProperties(SheetProperties().setTitle(title))
            )
        }
        sheets.spreadsheets().batchUpdate(
            id,
            BatchUpdateSpreadsheetRequest().setRequests(addReqs)
        ).execute()
    }

    /**
     * Pone la primera fila (cabecera) de cada hoja en negrita, sin
     * destruir las celdas existentes.
     *
     * Implementación anterior usaba `UpdateCellsRequest` con
     * `setRows([RowData([CellData])])`, que en la API de Sheets
     * REEMPLAZA las celdas del rango (no las conserva), por lo que
     * las cabeceras B-G (Gastos) y B-F (Ingresos) quedaban vacías tras
     * la exportación. Ahora usamos `RepeatCellRequest` que sólo
     * modifica el campo indicado en `fields`.
     */
    private fun formatHeaders(sheets: Sheets, id: String, sheetTitles: List<String>) {
        // Obtener los sheetId numéricos para construir los rangos de formato.
        val meta = sheets.spreadsheets().get(id).setIncludeGridData(false).execute()
        val sheetIdByTitle = meta.sheets.associate {
            (it.properties.title as String) to (it.properties.sheetId as Int)
        }

        val requests = mutableListOf<Request>()

        // 1) Poner la cabecera en negrita (no destructivo, vía repeatCell).
        sheetTitles.forEach { title ->
            val sheetId = sheetIdByTitle[title] ?: return@forEach
            requests.add(
                Request().setRepeatCell(
                    RepeatCellRequest()
                        .setFields("userEnteredFormat.textFormat.bold")
                        .setRange(
                            com.google.api.services.sheets.v4.model.GridRange()
                                .setSheetId(sheetId)
                                .setStartRowIndex(0)
                                .setEndRowIndex(1)
                        )
                        .setCell(
                            com.google.api.services.sheets.v4.model.CellData()
                                .setUserEnteredFormat(
                                    CellFormat().setTextFormat(
                                        TextFormat().setBold(true)
                                    )
                                )
                        )
                )
            )
        }

        // 2) Forzar formato numérico en las columnas de cantidades de
        //    las hojas AEAT para que SUM() las sume siempre (índices
        //    0-based): Recibidas F(base=5),G(IVA=6),H(cuota=7),I(recargo=8),
        //    J(IRPF=9),K(total=10) — Expedidas E(base=4),G(cuota=6),
        //    H(IRPF=7),I(total=8) — Nóminas C(devengado=2),D(líquido=3) —
        //    Productos B,C,D,E = 1..4
        val numericColumns = mapOf(
            "Facturas Recibidas" to listOf(5..5, 6..6, 7..7, 8..8, 9..9, 10..10),
            "Nóminas" to listOf(2..2, 3..3),
            "Productos" to listOf(1..1, 2..2, 3..3, 4..4, 5..5)
        )
    numericColumns.forEach { (title, ranges) ->
        val sheetId = sheetIdByTitle[title] ?: return@forEach
        ranges.forEach { colRange ->
            requests.add(
                Request().setRepeatCell(
                    RepeatCellRequest()
                        .setFields("userEnteredFormat.numberFormat")
                        .setRange(
                            com.google.api.services.sheets.v4.model.GridRange()
                                .setSheetId(sheetId)
                                .setStartRowIndex(1)
                                .setStartColumnIndex(colRange.first)
                                .setEndColumnIndex(colRange.last + 1)
                        )
                        .setCell(
                            com.google.api.services.sheets.v4.model.CellData()
                                .setUserEnteredFormat(
                                    CellFormat().setNumberFormat(
                                        NumberFormat()
                                            .setType("NUMBER")
                                            .setPattern("#,##0.00")
                                    )
                                )
                        )
                )
            )
        }
    }

        if (requests.isNotEmpty()) {
            sheets.spreadsheets().batchUpdate(
                id,
                BatchUpdateSpreadsheetRequest().setRequests(requests)
            ).execute()
        }
    }
}
