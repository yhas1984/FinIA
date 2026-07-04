package com.gastos.feature.backup

import android.content.Context
import android.content.Intent
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
 * Exporta los datos de FinAI a un Google Sheet nuevo con 4 hojas organizadas:
 * Gastos, Ingresos, Productos y Resumen. Usa la cuenta Google autenticada del
 * dispositivo (OAuth) vía GoogleSignIn.
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
        if (existingSpreadsheetId.isNotBlank()) {
            // Reutilizar spreadsheet existente — limpiar hojas y reescribir.
            spreadsheetId = existingSpreadsheetId
            clearSheets(sheetsService, spreadsheetId, listOf("Gastos", "Ingresos", "Productos", "Resumen"))
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
            val created: Spreadsheet = sheetsService.spreadsheets().create(spreadsheet).execute()
            spreadsheetId = created.spreadsheetId

            // Añadir las hojas restantes (Ingresos, Productos, Resumen).
            val addSheets = BatchUpdateSpreadsheetRequest().setRequests(
                listOf("Ingresos", "Productos", "Resumen").map { title ->
                    Request().setAddSheet(
                        AddSheetRequest().setProperties(SheetProperties().setTitle(title))
                    )
                }
            )
            sheetsService.spreadsheets().batchUpdate(spreadsheetId, addSheets).execute()
        }

        // Poblar las hojas.
        // Los ingresos pueden venir de la tabla `incomes` o de `invoices` con
        // tipo INGRESO (registros por chat/voz). Ambos van a la hoja Ingresos.
        val gastos = invoices.filter { it.tipo == InvoiceType.GASTO }
        val invoiceIngresos = invoices.filter { it.tipo == InvoiceType.INGRESO }
        writeGastos(sheetsService, spreadsheetId, gastos)
        writeIngresos(sheetsService, spreadsheetId, incomes, invoiceIngresos)
        writeProductos(sheetsService, spreadsheetId, products, invoices)
        writeResumen(sheetsService, spreadsheetId, gastos, incomes, invoiceIngresos, dateStr)

        // 4. Formatear cabeceras (negrita) en todas las hojas.
        formatHeaders(sheetsService, spreadsheetId, listOf("Gastos", "Ingresos", "Productos", "Resumen"))

        val url = "https://docs.google.com/spreadsheets/d/$spreadsheetId/edit"
        Pair(url, spreadsheetId)
    }

    private fun writeGastos(sheets: Sheets, id: String, gastos: List<Invoice>) {
        val values = mutableListOf<List<Any>>(
            listOf("Fecha", "Proveedor", "Total", "IVA %", "Moneda", "NIF Emisor", "Notas")
        )
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        gastos.forEach { inv ->
            values.add(
                listOf(
                    df.format(Date(inv.fecha)),
                    inv.proveedor,
                    inv.total,
                    inv.ivaPercent,
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
            listOf("Fecha", "Concepto", "Monto", "Moneda", "Fuente", "Notas")
        )
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        // Ingresos de la tabla `incomes`.
        incomes.forEach { inc ->
            values.add(
                listOf(
                    df.format(Date(inc.fecha)),
                    inc.concepto,
                    inc.monto,
                    inc.moneda,
                    inc.fuente ?: "",
                    inc.notas ?: ""
                )
            )
        }
        // Ingresos guardados como Invoice con tipo INGRESO (chat/voz/OCR).
        invoiceIngresos.forEach { inv ->
            values.add(
                listOf(
                    df.format(Date(inv.fecha)),
                    inv.proveedor,
                    inv.total,
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
            listOf("Descripción", "Cantidad", "Precio Unitario", "Subtotal", "IVA %", "Factura (Proveedor)")
        )
        // Mapa idFactura -> proveedor para enriquecer.
        val invMap = invoices.associate { it.id to it.proveedor }
        products.forEach { p ->
            values.add(
                listOf(
                    p.descripcion,
                    p.cantidad,
                    p.precioUnitario,
                    p.subtotal,
                    p.ivaPercent,
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
        // Usamos fórmulas SUM para que se recalculen automáticamente cuando
        // la sincronización en background añada filas a Gastos/Ingresos.
        val values = listOf(
            listOf("Resumen Financiero"),
            listOf("Fecha exportación", exportDate),
            listOf("Total Gastos", "=SUM(Gastos!C2:C)"),
            listOf("Total Ingresos", "=SUM(Ingresos!C2:C)"),
            listOf("Balance", "=B4-B3")
        )
        sheets.spreadsheets().values()
            .update(id, "Resumen!A1", ValueRange().setValues(values.map { it.map { v -> v.toString() } }))
            .setValueInputOption("USER_ENTERED")
            .execute()
    }

    /** Limpia el contenido de las hojas indicadas (mantiene estructura y cabeceras). */
    private fun clearSheets(sheets: Sheets, id: String, titles: List<String>) {
        val ranges = titles.map { "$it!A2:Z" }
        // Eliminar todas las filas desde la 2 en adelante en cada hoja.
        val requests = ranges.map { range ->
            // Limpiar valores para rango abierto A2:Z.
            sheets.spreadsheets().values().clear(id, range, null).execute()
        }
    }

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
}
