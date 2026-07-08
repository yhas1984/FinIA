package com.gastos.feature.backup

import android.content.Context
import android.content.Intent
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.Product
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.GenericUrl
import com.google.api.client.http.HttpRequest
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.HttpResponse
import com.google.api.client.http.HttpResponseException
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.AddSheetRequest
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest
import com.google.api.services.sheets.v4.model.CellFormat
import com.google.api.services.sheets.v4.model.Color
import com.google.api.services.sheets.v4.model.GridRange
import com.google.api.services.sheets.v4.model.GridProperties
import com.google.api.services.sheets.v4.model.NumberFormat
import com.google.api.services.sheets.v4.model.Request
import com.google.api.services.sheets.v4.model.SheetProperties
import com.google.api.services.sheets.v4.model.Spreadsheet
import com.google.api.services.sheets.v4.model.SpreadsheetProperties
import com.google.api.services.sheets.v4.model.TextFormat
import com.google.api.services.sheets.v4.model.UpdateCellsRequest
import com.google.api.services.sheets.v4.model.ValueRange
import org.json.JSONObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exporta los datos de FinAI a un Google Sheet nuevo con 4 hojas organizadas:
 * Gastos, Ingresos, Productos y Resumen. Usa la cuenta Google autenticada del
 * dispositivo (OAuth) vía GoogleSignIn.
 */
@Singleton
class SheetsExportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authManager: GoogleAuthManager
) {
    companion object {
        private const val TAG = "SheetsExportService"

        // URL del Google Cloud Console para habilitar Sheets API cuando el
        // proyecto no la tenga activada (error 403 SERVICE_DISABLED).
        // Es genérico: el usuario abre y selecciona su proyecto.
        const val ENABLE_SHEETS_API_URL =
            "https://console.cloud.google.com/apis/library/sheets.googleapis.com"
    }

    /**
     * Excepción tipada para que la UI pueda mostrar un mensaje útil sin
     * tener que parsear el JSON de respuesta de Google cada vez.
     */
    sealed class SheetsExportError(message: String) : Exception(message) {
        /** API habilitada pero el proyecto no la tiene activa (403). */
        class ServiceDisabled(val activationUrl: String) :
            SheetsExportError("Google Sheets API no habilitada en el proyecto.")

        /** Sin permisos para los scopes solicitados (403/insufficient). */
        class InsufficientPermissions(val missingScope: String?) :
            SheetsExportError("Faltan permisos de Google Sheets.")

        /** El token de acceso está caducado / la sesión es inválida. */
        class SessionExpired :
            SheetsExportError("La sesión de Google con Sheets ha caducado. Vuelve a iniciar sesión.")

        /** Otros errores genéricos de la API. */
        class Generic(detail: String) : SheetsExportError(detail)
    }

    /** Intent para lanzar el flujo de Sign-In con permisos de Sheets. */
    fun getSignInIntent(): Intent = authManager.getSignInIntent()

    /** ¿Hay una cuenta Google con permisos de Sheets concedidos? */
    fun isSignedIn(): Boolean = authManager.isSignedIn()

    fun getSignedInEmail(): String? = authManager.getSignedInEmail()

    /** Devuelve la cuenta autenticada, o null si no hay sesión con permisos. */
    fun getLastSignedInAccount(): GoogleSignInAccount? = authManager.getLastSignedInAccount()

    /**
     * No-op mantenido por compatibilidad. El token se refresca
     * automáticamente con [silentReauthenticate].
     */
    fun clearCachedToken() {
        // Intentionally empty: silentReauthenticate handles token refresh.
    }

    /**
     * Intenta re-autenticar con Google **en background** (sin mostrar UI).
     * Si el usuario sigue autenticado, refresca silenciosamente la sesión
     * y los tokens OAuth. Devuelve `true` si tuvo éxito.
     */
    suspend fun silentReauthenticate(): Boolean = authManager.silentReauthenticate()

    /**
     * Importa los datos desde un Google Sheet vinculado. Lee las hojas
     * "Gastos" e "Ingresos" y devuelve listas de [Invoice] e [Income]
     * listas para insertar en la BD local.
     *
     * Útil para recuperar datos tras reinstalar la app.
     */
    suspend fun importFromSheets(
        spreadsheetId: String
    ): Pair<List<Invoice>, List<Income>> = withContext(Dispatchers.IO) {
        val token = authManager.getAccessToken()
        val sheetsService = Sheets.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            BearerTokenInitializer(token)
        )
            .setApplicationName("FinAI")
            .build()

        val invoices = mutableListOf<Invoice>()
        val incomes = mutableListOf<Income>()

        // Leer hoja Gastos
        try {
            val gastosResponse = sheetsService.spreadsheets().values()
                .get(spreadsheetId, "Gastos!A2:K")
                .execute()
            val gastosValues = gastosResponse.getValues() ?: emptyList()
            gastosValues.forEachIndexed { idx, row ->
                val cells = row.map { it?.toString() ?: "" }
                if (cells.size < 7) return@forEachIndexed
                try {
                    val fechaStr = cells[0]
                    val fecha = parseSheetDate(fechaStr)
                    val proveedor = cells[1]
                    val baseImponible = cells.getOrNull(2)?.parseMoneyOrNull() ?: 0.0
                    val ivaPercent = cells.getOrNull(3)?.parseMoneyOrNull() ?: 21.0
                    val cuotaIva = cells.getOrNull(4)?.parseMoneyOrNull() ?: 0.0
                    val irpfPercent = cells.getOrNull(5)?.parseMoneyOrNull() ?: 0.0
                    val total = cells.getOrNull(6)?.parseMoneyOrNull() ?: 0.0
                    val moneda = cells.getOrNull(7)?.ifBlank { "EUR" } ?: "EUR"
                    val nifEmisor = cells.getOrNull(8)?.ifBlank { null }
                    val notas = cells.getOrNull(9)?.ifBlank { null }
                    invoices.add(
                        Invoice(
                            fecha = fecha,
                            proveedor = proveedor,
                            tipo = InvoiceType.GASTO,
                            moneda = moneda,
                            total = total,
                            ivaPercent = ivaPercent,
                            irpfPercent = irpfPercent,
                            baseImponible = baseImponible,
                            cuotaIva = cuotaIva,
                            nifEmisor = nifEmisor,
                            notas = notas
                        )
                    )
                } catch (_: Exception) { /* fila inválida, saltar */ }
            }
        } catch (e: GoogleJsonResponseException) {
            android.util.Log.w(TAG, "Hoja Gastos no accesible: ${e.statusCode}")
        }

        // Leer hoja Ingresos
        try {
            val ingresosResponse = sheetsService.spreadsheets().values()
                .get(spreadsheetId, "Ingresos!A2:F")
                .execute()
            val ingresosValues = ingresosResponse.getValues() ?: emptyList()
            ingresosValues.forEachIndexed { idx, row ->
                val cells = row.map { it?.toString() ?: "" }
                if (cells.size < 4) return@forEachIndexed
                try {
                    val fecha = parseSheetDate(cells[0])
                    val concepto = cells[1]
                    val monto = cells.getOrNull(2)?.parseMoneyOrNull() ?: 0.0
                    val moneda = cells.getOrNull(3)?.ifBlank { "EUR" } ?: "EUR"
                    val fuente = cells.getOrNull(4)?.ifBlank { null }
                    incomes.add(
                        Income(
                            fecha = fecha,
                            concepto = concepto,
                            monto = monto,
                            moneda = moneda,
                            fuente = fuente
                        )
                    )
                } catch (_: Exception) { /* fila inválida, saltar */ }
            }
        } catch (e: GoogleJsonResponseException) {
            android.util.Log.w(TAG, "Hoja Ingresos no accesible: ${e.statusCode}")
        }

        Pair(invoices, incomes)
    }

    /** Parsea fechas en formato YYYY-MM-DD o dd/MM/yyyy a epoch millis. */
    private fun parseSheetDate(dateStr: String): Long {
        if (dateStr.isBlank()) return System.currentTimeMillis()
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                .parse(dateStr)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            try {
                java.text.SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                    .parse(dateStr)?.time ?: System.currentTimeMillis()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }
        }
    }

    /** Extensión local para parsear importes (acepta coma y punto). */
    private fun String.parseMoneyOrNull(): Double? =
        this.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    /**
     * Crea un spreadsheet nuevo o, si [existingSpreadsheetId] no está vacío,
     * sobreescribe las hojas del spreadsheet existente con los datos actuales.
     *
     * Si falla por `GoogleAuthIOException`/`GoogleAuthException` (token
     * caducado), intenta **automáticamente** un re-auth silencioso via
     * [silentReauthenticate] y un retry. Si también falla, lanza
     * [SheetsExportError.SessionExpired] para que la UI muestre un mensaje
     * claro al usuario.
     */
    suspend fun exportToSheets(
        account: GoogleSignInAccount,
        invoices: List<Invoice>,
        incomes: List<Income>,
        products: List<Product>,
        existingSpreadsheetId: String = ""
    ): Pair<String, String> {
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        return try {
            withContext(Dispatchers.IO) {
                exportInternal(account, invoices, incomes, products, existingSpreadsheetId, dateStr)
            }
        } catch (e: com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAuthIOException) {
            // Token de acceso expirado/revocado. Re-auth silencioso + retry.
            android.util.Log.w(TAG, "exportToSheets token caducado. Re-auth…", e)
            val refreshed = silentReauthenticate()
            if (refreshed) {
                try {
                    withContext(Dispatchers.IO) {
                        exportInternal(account, invoices, incomes, products, existingSpreadsheetId, dateStr)
                    }
                } catch (e2: Exception) {
                    throw mapUnexpectedError(e2)
                }
            } else {
                throw SheetsExportError.SessionExpired()
            }
        } catch (e: com.google.android.gms.auth.GoogleAuthException) {
            android.util.Log.w(TAG, "exportToSheets GoogleAuthException", e)
            val refreshed = silentReauthenticate()
            if (refreshed) {
                try {
                    withContext(Dispatchers.IO) {
                        exportInternal(account, invoices, incomes, products, existingSpreadsheetId, dateStr)
                    }
                } catch (e2: Exception) {
                    throw mapUnexpectedError(e2)
                }
            } else {
                throw SheetsExportError.SessionExpired()
            }
        } catch (e: SheetsExportError) {
            throw e
        } catch (e: GoogleJsonResponseException) {
            throw mapApiError(e)
        } catch (e: Exception) {
            throw mapUnexpectedError(e)
        }
    }

    private fun mapUnexpectedError(e: Exception): SheetsExportError {
        android.util.Log.e(TAG, "exportToSheets error inesperado", e)
        return SheetsExportError.Generic(e.message ?: e.javaClass.simpleName)
    }

    private suspend fun exportInternal(
        account: GoogleSignInAccount,
        invoices: List<Invoice>,
        incomes: List<Income>,
        products: List<Product>,
        existingSpreadsheetId: String,
        dateStr: String
    ): Pair<String, String> {
        // Usamos el access token directamente desde GoogleAuthManager. Esto
        // evita el bug de `GoogleAccountCredential.usingOAuth2()` que cachea
        // el scope string con la combinación exacta de scopes — si difiere
        // de los scopes autorizados, Play Services falla con `ERROR`.
        val token = authManager.getAccessToken()

        val sheetsService = Sheets.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            BearerTokenInitializer(token)
        )
            .setApplicationName("FinAI")
            .build()

        val spreadsheetId: String
        if (existingSpreadsheetId.isNotBlank()) {
            // Reutilizar spreadsheet existente — limpiar hojas y reescribir.
            spreadsheetId = existingSpreadsheetId
            try {
                clearSheets(sheetsService, spreadsheetId, listOf("Gastos", "Ingresos", "Productos", "Resumen"))
            } catch (e: GoogleJsonResponseException) {
                throw mapApiError(e)
            }
        } else {
            // Crear spreadsheet nuevo con la primera hoja (Gastos).
            val spreadsheet = Spreadsheet()
                .setProperties(
                    SpreadsheetProperties().setTitle("FinAI - Exportación $dateStr")
                )
                .setSheets(
                    listOf(
                        com.google.api.services.sheets.v4.model.Sheet()
                            .setProperties(SheetProperties().setTitle("Gastos"))
                    )
                )
            val created: Spreadsheet = try {
                sheetsService.spreadsheets().create(spreadsheet).execute()
            } catch (e: GoogleJsonResponseException) {
                throw mapApiError(e)
            }
            spreadsheetId = created.spreadsheetId
                ?: throw SheetsExportError.Generic(
                    "La API devolvió una respuesta sin spreadsheetId. " +
                    "Probable causa: el Client ID Android no tiene autorizado " +
                    "el SHA1 del certificado firmante en Google Cloud Console. " +
                    "Ve a APIs & Services → Credentials → tu Client ID " +
                    "→ 'Add fingerprint' y añade: " +
                    "15:EB:D3:5F:EB:79:1C:6A:CC:55:A4:09:E0:AC:9D:65:5B:37:FE:3F"
                )

            // Añadir las hojas restantes (Ingresos, Productos, Resumen).
            val addSheets = BatchUpdateSpreadsheetRequest().setRequests(
                listOf("Ingresos", "Productos", "Resumen").map { title ->
                    Request().setAddSheet(
                        AddSheetRequest().setProperties(SheetProperties().setTitle(title))
                    )
                }
            )
            try {
                sheetsService.spreadsheets().batchUpdate(spreadsheetId, addSheets).execute()
            } catch (e: GoogleJsonResponseException) {
                throw mapApiError(e)
            }
        }

        // Poblar las hojas.
        val gastos = invoices.filter { it.tipo == InvoiceType.GASTO }
        val invoiceIngresos = invoices.filter { it.tipo == InvoiceType.INGRESO }
        writeGastos(sheetsService, spreadsheetId, gastos)
        writeIngresos(sheetsService, spreadsheetId, incomes, invoiceIngresos)
        writeProductos(sheetsService, spreadsheetId, products, invoices)
        writeResumen(sheetsService, spreadsheetId, gastos, incomes, invoiceIngresos, dateStr)

        // Formateo profesional: cabeceras, freeze, auto-width.
        val sheetNames = listOf("Gastos", "Ingresos", "Productos", "Resumen")
        formatHeaders(sheetsService, spreadsheetId, sheetNames)
        freezeFirstRow(sheetsService, spreadsheetId, sheetNames)
        autoResizeColumns(sheetsService, spreadsheetId, sheetNames)

        val url = "https://docs.google.com/spreadsheets/d/$spreadsheetId/edit"
        return Pair(url, spreadsheetId)
    }

    private fun fmt(d: Double): String = String.format(java.util.Locale.US, "%.2f", d)

    private fun writeGastos(sheets: Sheets, id: String, gastos: List<Invoice>) {
        val values = mutableListOf<List<Any>>(
            listOf(
                "Fecha", "Proveedor", "Base Imponible (€)",
                "IVA %", "Cuota IVA (€)", "IRPF %", "Cuota IRPF (€)",
                "Total (€)", "Moneda", "NIF Emisor", "Notas"
            )
        )
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        gastos.forEach { inv ->
            values.add(
                listOf(
                    df.format(Date(inv.fecha)),
                    inv.proveedor,
                    fmt(inv.baseImponible),
                    fmt(inv.ivaPercent),
                    fmt(inv.cuotaIva),
                    fmt(inv.irpfPercent),
                    fmt(inv.cuotaIrpf),
                    fmt(inv.total),
                    inv.moneda,
                    inv.nifEmisor ?: "",
                    inv.notas ?: ""
                )
            )
        }
        sheets.spreadsheets().values()
            .update(id, "Gastos!A1", ValueRange().setValues(values.map { it.map { v -> v.toString() } }))
            .setValueInputOption("USER_ENTERED")
            .execute()
    }

    private fun writeIngresos(
        sheets: Sheets,
        id: String,
        incomes: List<Income>,
        invoiceIngresos: List<Invoice>
    ) {
        val values = mutableListOf<List<Any>>(
            listOf("Fecha", "Concepto", "Monto (€)", "Moneda", "Fuente", "Notas")
        )
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        incomes.forEach { inc ->
            values.add(
                listOf(
                    df.format(Date(inc.fecha)),
                    inc.concepto,
                    fmt(inc.monto),
                    inc.moneda,
                    inc.fuente ?: "",
                    inc.notas ?: ""
                )
            )
        }
        invoiceIngresos.forEach { inv ->
            values.add(
                listOf(
                    df.format(Date(inv.fecha)),
                    inv.proveedor,
                    fmt(inv.total),
                    inv.moneda,
                    inv.nifEmisor ?: "",
                    inv.notas ?: ""
                )
            )
        }
        sheets.spreadsheets().values()
            .update(id, "Ingresos!A1", ValueRange().setValues(values.map { it.map { v -> v.toString() } }))
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
            listOf(
                "Descripción", "Cantidad", "Precio Unitario (€)",
                "Subtotal (€)", "IVA %", "Factura (Proveedor)"
            )
        )
        val invMap = invoices.associate { it.id to it.proveedor }
        products.forEach { p ->
            values.add(
                listOf(
                    p.descripcion,
                    fmt(p.cantidad),
                    fmt(p.precioUnitario),
                    fmt(p.subtotal),
                    fmt(p.ivaPercent),
                    invMap[p.invoiceId] ?: ""
                )
            )
        }
        sheets.spreadsheets().values()
            .update(id, "Productos!A1", ValueRange().setValues(values.map { it.map { v -> v.toString() } }))
            .setValueInputOption("USER_ENTERED")
            .execute()
    }

    private fun writeResumen(
        sheets: Sheets,
        id: String,
        gastos: List<Invoice>,
        incomes: List<Income>,
        invoiceIngresos: List<Invoice>,
        exportDate: String
    ) {
        // Antes escribíamos fórmulas (=SUM(...)) que se quedaban en 0 si no
        // había datos en ese momento y, peor, mezclaban conceptos: Total
        // Gastos sumaba la columna de Base Imponible en lugar del Total
        // (IVA incluido). Ahora escribimos valores absolutos calculados a
        // partir de los datos en el momento de la exportación, con un
        // desglose claro que el usuario puede leer de un vistazo.
        //
        // Para sincronizaciones incrementales futuras, las hojas Gastos /
        // Ingresos pueden contener filas añadidas por SheetsSyncManager;
        // recalculamos los totales usando las filas presentes en la
        // exportación actual (no fórmulas que dependan de columnas
        // específicas).
        val totalGastosBruto = gastos.sumOf { it.total }
        val totalGastosBase = gastos.sumOf { it.baseImponible }
        val totalGastosIva = gastos.sumOf { it.cuotaIva }
        val totalGastosIrpf = gastos.sumOf { it.cuotaIrpf }

        val totalIngresosNeto = incomes.sumOf { it.monto } +
            invoiceIngresos.sumOf { it.total }
        val totalIngresosBase = incomes.sumOf {
            if (it.ivaPercent > 0) it.monto / (1.0 + it.ivaPercent / 100.0) else it.monto
        } + invoiceIngresos.sumOf { it.baseImponible }
        val totalIngresosIva = incomes.sumOf {
            if (it.ivaPercent > 0) it.baseImponible * it.ivaPercent / 100.0 else 0.0
        } + invoiceIngresos.sumOf { it.cuotaIva }

        val balance = totalIngresosNeto - totalGastosBruto + totalGastosIrpf

        val fmt = "%." + 2 + "f"
        val values = listOf(
            listOf("Resumen Financiero"),
            listOf("Fecha exportación", exportDate),
            listOf("", ""),
            listOf("GASTOS", ""),
            listOf("  Total facturado (IVA incl.)", totalGastosBruto.formatMoney()),
            listOf("  Base imponible", totalGastosBase.formatMoney()),
            listOf("  IVA repercutido", totalGastosIva.formatMoney()),
            listOf("  IRPF retenido", totalGastosIrpf.formatMoney()),
            listOf("", ""),
            listOf("INGRESOS", ""),
            listOf("  Total recibido", totalIngresosNeto.formatMoney()),
            listOf("  Base imponible", totalIngresosBase.formatMoney()),
            listOf("  IVA repercutido", totalIngresosIva.formatMoney()),
            listOf("", ""),
            listOf("BALANCE NETO (Ingresos - Gastos + IRPF)", balance.formatMoney())
        )
        sheets.spreadsheets().values()
            .update(id, "Resumen!A1", ValueRange().setValues(values.map { it.map { v -> v.toString() } }))
            .setValueInputOption("USER_ENTERED")
            .execute()
    }

    private fun Double.formatMoney(): String =
        java.lang.String.format(java.util.Locale.US, "%.2f", this)

    /** Limpia el contenido de las hojas indicadas (mantiene estructura y cabeceras). */
    private fun clearSheets(sheets: Sheets, id: String, titles: List<String>) {
        val ranges = titles.map { "$it!A2:Z" }
        // Eliminar todas las filas desde la 2 en adelante en cada hoja.
        val requests = ranges.map { range ->
            // Limpiar valores para rango abierto A2:Z.
            sheets.spreadsheets().values().clear(id, range, null).execute()
        }
    }

    /** Congela la primera fila (cabeceras) en las hojas indicadas. */
    private fun freezeFirstRow(sheets: Sheets, id: String, sheetNames: List<String>) {
        val meta = sheets.spreadsheets().get(id).setIncludeGridData(false).execute()
        val updates = meta.sheets.mapNotNull { sheet ->
            val title = sheet.properties.title as? String ?: return@mapNotNull null
            if (title !in sheetNames) return@mapNotNull null
            Request().setUpdateSheetProperties(
                com.google.api.services.sheets.v4.model.UpdateSheetPropertiesRequest()
                    .setProperties(sheet.properties.setGridProperties(
                        GridProperties().setFrozenRowCount(1)
                    ))
                    .setFields("gridProperties.frozenRowCount")
            )
        }
        if (updates.isNotEmpty()) {
            sheets.spreadsheets().batchUpdate(id, BatchUpdateSpreadsheetRequest().setRequests(updates)).execute()
        }
    }

    /** Ajusta el ancho de columnas automáticamente. */
    private fun autoResizeColumns(sheets: Sheets, id: String, sheetNames: List<String>) {
        val meta = sheets.spreadsheets().get(id).setIncludeGridData(false).execute()
        val requests = meta.sheets.mapNotNull { sheet ->
            val title = sheet.properties.title as? String ?: return@mapNotNull null
            if (title !in sheetNames) return@mapNotNull null
            Request().setAutoResizeDimensions(
                com.google.api.services.sheets.v4.model.AutoResizeDimensionsRequest()
                    .setDimensions(
                        com.google.api.services.sheets.v4.model.DimensionRange()
                            .setSheetId(sheet.properties.sheetId)
                            .setDimension("COLUMNS")
                            .setStartIndex(0)
                            .setEndIndex(10)
                    )
            )
        }
        if (requests.isNotEmpty()) {
            sheets.spreadsheets().batchUpdate(id, BatchUpdateSpreadsheetRequest().setRequests(requests)).execute()
        }
    }

    /** Añade formato condicional: fondo verde si balance >=0 en Resumen. */
    /** Pone la primera fila (cabecera) de cada hoja en negrita. */
    private fun formatHeaders(sheets: Sheets, id: String, sheetTitles: List<String>) {
        // Obtener los sheetId numéricos para construir los rangos de formato.
        val meta = sheets.spreadsheets().get(id).setIncludeGridData(false).execute()
        val sheetIdByTitle = meta.sheets.associate {
            (it.properties.title as String) to (it.properties.sheetId as Int)
        }

        val requests = sheetTitles.mapNotNull { title ->
            val sheetId = sheetIdByTitle[title] ?: return@mapNotNull null
            Request().setUpdateCells(
                UpdateCellsRequest()
                    .setFields("userEnteredFormat")
                    .setRange(
                        com.google.api.services.sheets.v4.model.GridRange()
                            .setSheetId(sheetId)
                            .setStartRowIndex(0)
                            .setEndRowIndex(1)
                    )
                    .setRows(
                        listOf(
                            com.google.api.services.sheets.v4.model.RowData().setValues(
                                listOf(
                                    com.google.api.services.sheets.v4.model.CellData()
                                        .setUserEnteredFormat(
                                            CellFormat().setTextFormat(
                                                TextFormat().setBold(true)
                                            )
                                        )
                                )
                            )
                        )
                    )
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
     * Mapea una [GoogleJsonResponseException] a nuestra jerarquía tipada
     * [SheetsExportError] para que la UI muestre mensajes claros. Loggea el
     * detalle completo antes de re-lanzar.
     */
    private fun mapApiError(e: GoogleJsonResponseException): SheetsExportError {
        val status = e.statusCode
        val body = e.content ?: return SheetsExportError.Generic(e.message ?: "Error $status")
        android.util.Log.w(TAG, "Sheets API error $status: $body")

        return when {
            // 403 + reason `SERVICE_DISABLED` o `accessNotConfigured` → la API
            // no está habilitada en el proyecto GCP vinculado al OAuth client.
            // Esto NO es un bug del código; requiere activación manual en
            // https://console.cloud.google.com
            status == 403 && (
                body.contains("accessNotConfigured", ignoreCase = true) ||
                body.contains("SERVICE_DISABLED", ignoreCase = true)
            ) -> {
                // Extraer activationUrl del JSON si está disponible.
                val activationUrl = try {
                    JSONObject(body).optJSONArray("details")
                        ?.let { it.optJSONObject(0) }
                        ?.optJSONObject("metadata")
                        ?.optString("activationUrl", ENABLE_SHEETS_API_URL)
                        ?: ENABLE_SHEETS_API_URL
                } catch (_: Exception) {
                    ENABLE_SHEETS_API_URL
                }
                SheetsExportError.ServiceDisabled(activationUrl)
            }

            status == 403 -> SheetsExportError.InsufficientPermissions(null)

            else -> {
                // Para 5xx, 429, etc. extrae el `message` humano del JSON si se puede.
                val humanMessage = try {
                    JSONObject(body).optString("message", e.message ?: "Error $status")
                } catch (_: Exception) {
                    e.message ?: "Error $status"
                }
                SheetsExportError.Generic(humanMessage)
            }
        }
    }
}

/**
 * Inicializador que inyecta un Bearer token en cada petición HTTP.
 *
 * Es la alternativa moderna a `GoogleAccountCredential.usingOAuth2()` que
 * evita el bug del scope cacheado de Play Services. Usamos directamente
 * el access token que devuelve `GoogleAuthManager.getAccessToken()`.
 *
 * El token se pasa por valor en la construcción; si se necesita rotar,
 * se re-construye el `Sheets` service (cosa que hacemos en `exportToSheets`
 * tras un re-auth silencioso).
 *
 * NOTA: definido como `internal` para que `SheetsSyncManager` pueda
 * reutilizar la misma clase.
 */
internal class BearerTokenInitializer(
    private val accessToken: String
) : HttpRequestInitializer {
    override fun initialize(httpRequest: HttpRequest) {
        httpRequest.headers.setAuthorization("Bearer $accessToken")
        // Importante: SI lanzar excepciones en errores HTTP, para que la capa
        // superior pueda capturarlas (GoogleJsonResponseException, etc.).
        // Si esto es false, los errores se tragan silenciosamente.
        httpRequest.setThrowExceptionOnExecuteError(true)
        httpRequest.setReadTimeout(30_000)
        httpRequest.setConnectTimeout(15_000)
    }
}
