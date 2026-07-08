package com.gastos.feature.backup

import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.Product
import com.gastos.repository.IncomeRepository
import com.gastos.repository.InvoiceRepository
import com.gastos.repository.ProductRepository
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

data class BackupUiState(
    val isSignedIn: Boolean = false,
    val email: String? = null,
    val hasSheetLink: Boolean = false,
    val isLoading: Boolean = false,
    val isExporting: Boolean = false,
    val isExportingSheets: Boolean = false,
    val backupResult: BackupResult? = null,
    val exportResult: BackupResult? = null,
    val sheetsUrl: String? = null,
    val localBackups: List<File> = emptyList(),
    val error: String? = null,
    /** Si el último error es "Sheets API no habilitada", contiene la URL de activación. */
    val apiActivationUrl: String? = null,
    /** Mensaje de estado del último sync automático (gasto/ingreso recién creado). */
    val syncStatusMessage: String? = null,
    /** true si el último sync automático salió bien. */
    val lastSyncSuccess: Boolean = true
)

data class BackupResult(
    val success: Boolean,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val sharedFile: File? = null
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val sheetsExportService: SheetsExportService,
    private val sheetsSyncManager: SheetsSyncManager,
    private val invoiceRepository: InvoiceRepository,
    private val incomeRepository: IncomeRepository,
    private val productRepository: ProductRepository
) : ViewModel() {

    companion object {
        private const val TAG_SHEETS = "BackupViewModel"
    }

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    init {
        checkSignInStatus()
        // Observamos el estado del sync automático tras cada gasto/ingreso
        // para reflejarlo en la pantalla de Backup.
        viewModelScope.launch {
            sheetsSyncManager.status.collectLatest { result ->
                if (result is SheetsSyncManager.SyncResult.Failure) {
                    val warningMsg = when (result.reason) {
                        SheetsSyncManager.SyncResult.Failure.Reason.NO_SHEET_LINKED ->
                            "Tienes gastos/ingresos sin sincronizar con Google Sheets. " +
                                "Ve a Backup y pulsa \"Exportar a Google Sheets\"."
                        SheetsSyncManager.SyncResult.Failure.Reason.NO_ACCOUNT ->
                            "Sesión de Google caducada. Vuelve a iniciar sesión."
                        SheetsSyncManager.SyncResult.Failure.Reason.UNAUTHORIZED ->
                            "Google rechazó el token. Vuelve a iniciar sesión."
                        SheetsSyncManager.SyncResult.Failure.Reason.API_DISABLED ->
                            "Sheets API no habilitada en el proyecto GCP."
                        else -> "No se pudo sincronizar con Google Sheets."
                    }
                    _uiState.update {
                        it.copy(syncStatusMessage = warningMsg, lastSyncSuccess = false)
                    }
                } else if (result is SheetsSyncManager.SyncResult.Success) {
                    _uiState.update {
                        it.copy(
                            syncStatusMessage = "Última sincronización: ${result.sheet} OK",
                            lastSyncSuccess = true
                        )
                    }
                }
            }
        }
    }

    private fun checkSignInStatus() {
        _uiState.update {
            it.copy(
                isSignedIn = sheetsExportService.isSignedIn(),
                email = sheetsExportService.getSignedInEmail(),
                hasSheetLink = sheetsSyncManager.isEnabled()
            )
        }
    }

    /** Devuelve el Intent para lanzar el flujo de Sign-In de Google con scope Sheets. */
    fun getSignInIntent(): Intent = sheetsExportService.getSignInIntent()

    /** Procesa el resultado del Sign-In (desde StartActivityForResult). */
    fun handleSignInResult(data: Intent?) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account: GoogleSignInAccount = task.getResult(ApiException::class.java)
            _uiState.update {
                it.copy(
                    isSignedIn = true,
                    email = account.email,
                    error = null
                )
            }
        } catch (e: ApiException) {
            _uiState.update {
                it.copy(error = "Error al iniciar sesión: ${e.statusCode}")
            }
        }
    }

    /**
     * Exporta los datos a un Google Sheet nuevo. Requiere sesión iniciada.
     */
    fun exportToSheets() {
        android.util.Log.e("BackupVM", "1-exportToSheets called")
        viewModelScope.launch {
            android.util.Log.e("BackupVM", "2-launched")
            _uiState.update {
                it.copy(isExportingSheets = true, sheetsUrl = null, error = null, apiActivationUrl = null)
            }
            try {
                val account = sheetsExportService.getLastSignedInAccount()
                android.util.Log.e("BackupVM", "3-account=$account, isSignedIn=${sheetsExportService.isSignedIn()}")
                if (account == null || !sheetsExportService.isSignedIn()) {
                    android.util.Log.e("BackupVM", "4-not signed in")
                    _uiState.update { it.copy(isExportingSheets = false, error = "Debes iniciar sesión con Google primero.") }
                    return@launch
                }
                val (invoices, incomes, products) = loadData()
                android.util.Log.e("BackupVM", "5-data=${invoices.size}i ${incomes.size}inc ${products.size}p")
                val existingId = sheetsSyncManager.getStoredId()
                android.util.Log.e("BackupVM", "6-existingId=$existingId")
                val (url, spreadsheetId) = sheetsExportService.exportToSheets(account, invoices, incomes, products, existingId)
                android.util.Log.e("BackupVM", "7-SUCCESS url=$url id=$spreadsheetId")
                sheetsSyncManager.setSpreadsheetId(spreadsheetId)
                _uiState.update { it.copy(isExportingSheets = false, sheetsUrl = url) }
            } catch (e: SheetsExportService.SheetsExportError) {
                android.util.Log.e("BackupVM", "8-SheetsExportError ${e.javaClass.simpleName}", e)
                // Si la API no está habilitada en el proyecto GCP, mostramos un
                // enlace directo a la consola para que el usuario (o admin)
                // pueda activarla en un click.
                val activationUrl = (e as? SheetsExportService.SheetsExportError.ServiceDisabled)?.activationUrl
                _uiState.update {
                    it.copy(
                        isExportingSheets = false,
                        error = e.message ?: "Error desconocido",
                        apiActivationUrl = activationUrl
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("BackupVM", "8-EXCEPTION", e)
                _uiState.update { it.copy(isExportingSheets = false, error = "Error: ${e.message}") }
            }
        }
    }

    fun clearSheetsResult() {
        _uiState.update { it.copy(sheetsUrl = null, error = null, apiActivationUrl = null) }
    }

    /**
     * Importa todos los datos desde el Google Sheet vinculado a la BD local.
     * Útil para recuperar datos tras reinstalar la app.
     */
    fun importFromSheets() {
        viewModelScope.launch {
            _uiState.update { it.copy(isExportingSheets = true, error = null) }
            try {
                val account = sheetsExportService.getLastSignedInAccount()
                val existingId = sheetsSyncManager.getStoredId()
                if (account == null || existingId.isBlank()) {
                    _uiState.update {
                        it.copy(isExportingSheets = false, error = "No hay sheet vinculado. Exporta primero.")
                    }
                    return@launch
                }
                val (invoices, incomes) = sheetsExportService.importFromSheets(existingId)
                // Insertar en BD local (batch).
                invoices.forEach { invoiceRepository.insertInvoice(it) }
                incomes.forEach { incomeRepository.insertIncome(it) }
                _uiState.update {
                    it.copy(
                        isExportingSheets = false,
                        error = null,
                        sheetsUrl = "imported:${invoices.size}gastos-${incomes.size}ingresos"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isExportingSheets = false, error = "Error al importar: ${e.message}")
                }
            }
        }
    }

    /** Limpia el mensaje de estado del último sync automático. */
    fun clearSyncStatusMessage() {
        _uiState.update { it.copy(syncStatusMessage = null) }
    }

    /** Limpia el error explícito al pulsar "Aceptar". */
    fun clearError() {
        _uiState.update { it.copy(error = null, apiActivationUrl = null) }
    }

    /**
     * Re-autentica en background con `silentSignIn` de Play Services (sin
     * mostrar UI) y vuelve a intentar la última exportación. Si el usuario
     * sigue autenticado con los scopes correctos, esto resuelve el caso
     * "token caducado en cache" sin necesidad de logout/login manual.
     */
    fun retryWithSilentReauth() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isExportingSheets = true, error = null, apiActivationUrl = null)
            }
            val refreshed = sheetsExportService.silentReauthenticate()
            Log.d(TAG_SHEETS, "retryWithSilentReauth: refreshed=$refreshed")
            if (!refreshed) {
                _uiState.update {
                    it.copy(
                        isExportingSheets = false,
                        error = "No se pudo renovar la sesión con Google automáticamente. " +
                                "Comprueba tu conexión a internet o espera unos minutos."
                    )
                }
                return@launch
            }
            // Reintentar la operación de sync
            syncAllToSheets()
        }
    }

    /** Fuerza la sincronización de todos los datos existentes al sheet vinculado. */
    fun syncAllToSheets() {
        viewModelScope.launch {
            _uiState.update { it.copy(isExportingSheets = true, sheetsUrl = null, error = null, apiActivationUrl = null) }
            try {
                val account = sheetsExportService.getLastSignedInAccount()
                when {
                    account == null -> {
                        _uiState.update {
                            it.copy(
                                isExportingSheets = false,
                                error = "Sesión de Google caducada. Toca \"Iniciar sesión con Google\" arriba."
                            )
                        }
                        return@launch
                    }
                    !sheetsExportService.isSignedIn() -> {
                        _uiState.update {
                            it.copy(
                                isExportingSheets = false,
                                error = "Faltan permisos de Google Sheets. Inicia sesión de nuevo y concede los permisos."
                            )
                        }
                        return@launch
                    }
                }
                val (invoices, incomes, products) = loadData()
                Log.d(TAG_SHEETS, "Sincronizando ${invoices.size} invoices, ${incomes.size} incomes, ${products.size} products al sheet")

                // Si no existe sheet vinculado, exportToSheets lo crea.
                val existingId = sheetsSyncManager.getStoredId()
                val (url, newId) = sheetsExportService.exportToSheets(
                    account, invoices, incomes, products, existingId
                )
                sheetsSyncManager.setSpreadsheetId(newId)

                _uiState.update {
                    it.copy(
                        isExportingSheets = false,
                        sheetsUrl = url,
                        syncStatusMessage = "✓ Re-sincronizado: ${invoices.size} facturas + ${incomes.size} ingresos",
                        lastSyncSuccess = true
                    )
                }
                Log.d(TAG_SHEETS, "✓ Sincronización completa. URL=$url")
            } catch (e: SheetsExportService.SheetsExportError) {
                val activationUrl = (e as? SheetsExportService.SheetsExportError.ServiceDisabled)?.activationUrl
                _uiState.update {
                    it.copy(
                        isExportingSheets = false,
                        error = e.message ?: "Error desconocido",
                        apiActivationUrl = activationUrl
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG_SHEETS, "syncAllToSheets fallo", e)
                val msg = e.message ?: e.javaClass.simpleName
                _uiState.update {
                    it.copy(isExportingSheets = false, error = "Error al sincronizar: $msg")
                }
            }
        }
    }

    private suspend fun loadData(): Triple<List<Invoice>, List<Income>, List<Product>> {
        val invoices = invoiceRepository.getAllInvoices().first()
        val incomes = incomeRepository.getAllIncomes().first()
        val products = productRepository.getAllProducts().first()
        return Triple(invoices, incomes, products)
    }

    fun exportToCsv(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, exportResult = null) }

            try {
                val (invoices, incomes, products) = loadData()

                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    val csvContent = buildString {
                        append("Tipo,ID,Fecha,Concepto,Monto,Moneda,IVA%,IRPF%,Devengado,Neto,Notas\n")

                        invoices.forEach { invoice ->
                            append(
                                buildString {
                                    append(if (invoice.tipo == InvoiceType.GASTO) "Gasto" else "Ingreso")
                                    append(",")
                                    append(invoice.id)
                                    append(",")
                                    append(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(invoice.fecha)))
                                    append(",")
                                    append("\"${invoice.proveedor.replace("\"", "\"\"")}\"")
                                    append(",")
                                    append(invoice.total)
                                    append(",")
                                    append(invoice.moneda)
                                    append(",")
                                    append(invoice.ivaPercent)
                                    append(",")
                                    append(invoice.irpfPercent)
                                    append(",")
                                    append("")
                                    append(",")
                                    append("")
                                    append(",")
                                    append("\"${(invoice.notas ?: "").replace("\"", "\"\"")}\"")
                                    append("\n")
                                }
                            )
                        }

                        products.forEach { product ->
                            append(
                                buildString {
                                    append("Producto")
                                    append(",")
                                    append(product.id)
                                    append(",")
                                    append(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(product.createdAt)))
                                    append(",")
                                    append("\"${product.descripcion.replace("\"", "\"\"")}\"")
                                    append(",")
                                    append(product.subtotal)
                                    append(",")
                                    append("EUR")
                                    append(",")
                                    append(product.ivaPercent)
                                    append(",")
                                    append("0")
                                    append(",")
                                    append("")
                                    append(",")
                                    append("")
                                    append(",")
                                    append("\"\"")
                                    append("\n")
                                }
                            )
                        }

                        incomes.forEach { income ->
                            append(
                                buildString {
                                    append("Ingreso")
                                    append(",")
                                    append(income.id)
                                    append(",")
                                    append(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(income.fecha)))
                                    append(",")
                                    append("\"${income.concepto.replace("\"", "\"\"")}\"")
                                    append(",")
                                    append(income.monto)
                                    append(",")
                                    append(income.moneda)
                                    append(",")
                                    append(income.ivaPercent)
                                    append(",")
                                    append(income.irpfPercent)
                                    append(",")
                                    append(income.totalDevengado)
                                    append(",")
                                    append(income.totalNeto)
                                    append(",")
                                    append("\"${(income.notas ?: "").replace("\"", "\"\"")}\"")
                                    append("\n")
                                }
                            )
                        }

                        append("\n")
                        append("RESUMEN\n")
                        val totalGastos = invoices.filter { it.tipo == InvoiceType.GASTO }.sumOf { it.total }
                        val totalIngresos = incomes.sumOf { it.monto } + invoices.filter { it.tipo == InvoiceType.INGRESO }.sumOf { it.total }
                        append("Total Gastos,${totalGastos}\n")
                        append("Total Ingresos,${totalIngresos}\n")
                        append("Balance,${totalIngresos - totalGastos}\n")
                        append("Fecha exportación,${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}\n")
                    }

                    outputStream.write(csvContent.toByteArray())
                }

                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportResult = BackupResult(
                            success = true,
                            message = "CSV exportado: ${invoices.size} facturas, ${products.size} productos, ${incomes.size} ingresos"
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportResult = BackupResult(
                            success = false,
                            message = "Error al exportar CSV: ${e.message}"
                        )
                    )
                }
            }
        }
    }

    fun exportToPdf(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, exportResult = null) }

            try {
                val (invoices, incomes, products) = loadData()
                val fmt = java.text.NumberFormat.getCurrencyInstance(Locale("es", "ES"))
                val df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

                val pdfDocument = PdfDocument()
                var pageNumber = 1
                var pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                var page = pdfDocument.startPage(pageInfo)
                var canvas = page.canvas
                var y = 50f
                val paint = android.graphics.Paint()
                val titlePaint = android.graphics.Paint().apply {
                    textSize = 18f
                    isFakeBoldText = true
                    color = android.graphics.Color.parseColor("#6750A4")
                }
                val headerPaint = android.graphics.Paint().apply {
                    textSize = 12f
                    isFakeBoldText = true
                    color = android.graphics.Color.BLACK
                }
                val bodyPaint = android.graphics.Paint().apply {
                    textSize = 10f
                    color = android.graphics.Color.DKGRAY
                }

                // Title
                canvas.drawText("FinAI - Informe Financiero", 40f, y, titlePaint)
                y += 30f
                canvas.drawText("Generado: ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())}", 40f, y, bodyPaint)
                y += 30f

                // Summary
                val totalGastos = invoices.filter { it.tipo == InvoiceType.GASTO }.sumOf { it.total }
                val totalIngresos = incomes.sumOf { it.monto } + invoices.filter { it.tipo == InvoiceType.INGRESO }.sumOf { it.total }

                canvas.drawText("RESUMEN", 40f, y, headerPaint)
                y += 20f
                canvas.drawText("Total Gastos: ${fmt.format(totalGastos)}", 60f, y, bodyPaint)
                y += 18f
                canvas.drawText("Total Ingresos: ${fmt.format(totalIngresos)}", 60f, y, bodyPaint)
                y += 18f
                canvas.drawText("Balance: ${fmt.format(totalIngresos - totalGastos)}", 60f, y, bodyPaint)
                y += 30f

                // Gastos
                canvas.drawText("GASTOS", 40f, y, headerPaint)
                y += 20f
                invoices.filter { it.tipo == InvoiceType.GASTO }.forEach { inv ->
                    if (y > 780f) {
                        pdfDocument.finishPage(page)
                        pageNumber += 1
                        pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                        page = pdfDocument.startPage(pageInfo)
                        canvas = page.canvas
                        y = 50f
                    }
                    canvas.drawText("${df.format(Date(inv.fecha))} - ${inv.proveedor}: ${fmt.format(inv.total)}", 60f, y, bodyPaint)
                    y += 16f
                }
                y += 15f

                // Ingresos
                canvas.drawText("INGRESOS", 40f, y, headerPaint)
                y += 20f
                incomes.forEach { inc ->
                    if (y > 780f) {
                        pdfDocument.finishPage(page)
                        pageNumber += 1
                        pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                        page = pdfDocument.startPage(pageInfo)
                        canvas = page.canvas
                        y = 50f
                    }
                    canvas.drawText("${df.format(Date(inc.fecha))} - ${inc.concepto}: ${fmt.format(inc.monto)}", 60f, y, bodyPaint)
                    y += 16f
                }

                pdfDocument.finishPage(page)

                try {
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        pdfDocument.writeTo(outputStream)
                    }
                } finally {
                    pdfDocument.close()
                }

                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportResult = BackupResult(
                            success = true,
                            message = "PDF exportado correctamente"
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportResult = BackupResult(
                            success = false,
                            message = "Error al exportar PDF: ${e.message}"
                        )
                    )
                }
            }
        }
    }

    fun shareBackup(context: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, exportResult = null) }

            try {
                val (invoices, incomes, products) = loadData()
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val exportDir = File(context.filesDir, "exports")
                if (!exportDir.exists()) exportDir.mkdirs()
                val csvFile = File(exportDir, "finai_backup_$timestamp.csv")

                csvFile.writeText(buildString {
                    append("Tipo,ID,Fecha,Concepto,Monto,Moneda,IVA%,IRPF%,Devengado,Neto,Notas\n")

                    invoices.forEach { invoice ->
                        append(
                            buildString {
                                append(if (invoice.tipo == InvoiceType.GASTO) "Gasto" else "Ingreso")
                                append(",")
                                append(invoice.id)
                                append(",")
                                append(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(invoice.fecha)))
                                append(",")
                                append("\"${invoice.proveedor.replace("\"", "\"\"")}\"")
                                append(",")
                                append(invoice.total)
                                append(",")
                                append(invoice.moneda)
                                append(",")
                                append(invoice.ivaPercent)
                                append(",")
                                append(invoice.irpfPercent)
                                append(",")
                                append("")
                                append(",")
                                append("")
                                append(",")
                                append("\"${(invoice.notas ?: "").replace("\"", "\"\"")}\"")
                                append("\n")
                            }
                        )
                    }

                    products.forEach { product ->
                        append(
                            buildString {
                                append("Producto")
                                append(",")
                                append(product.id)
                                append(",")
                                append(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(product.createdAt)))
                                append(",")
                                append("\"${product.descripcion.replace("\"", "\"\"")}\"")
                                append(",")
                                append(product.subtotal)
                                append(",")
                                append("EUR")
                                append(",")
                                append(product.ivaPercent)
                                append(",")
                                append("0")
                                append(",")
                                append("")
                                append(",")
                                append("")
                                append(",")
                                append("\"\"")
                                append("\n")
                            }
                        )
                    }

                    incomes.forEach { income ->
                        append(
                            buildString {
                                append("Ingreso")
                                append(",")
                                append(income.id)
                                append(",")
                                append(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(income.fecha)))
                                append(",")
                                append("\"${income.concepto.replace("\"", "\"\"")}\"")
                                append(",")
                                append(income.monto)
                                append(",")
                                append(income.moneda)
                                append(",")
                                append(income.ivaPercent)
                                append(",")
                                append(income.irpfPercent)
                                append(",")
                                append(income.totalDevengado)
                                append(",")
                                append(income.totalNeto)
                                append(",")
                                append("\"${(income.notas ?: "").replace("\"", "\"\"")}\"")
                                append("\n")
                            }
                        )
                    }
                })

                val fileUri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    csvFile
                )

                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(android.content.Intent.EXTRA_STREAM, fileUri)
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "FinAI Backup - $timestamp")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooser = android.content.Intent.createChooser(shareIntent, "Compartir backup FinAI")
                chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(chooser)
                    _uiState.update {
                        it.copy(
                            isExporting = false,
                            exportResult = BackupResult(
                                success = true,
                                message = "Backup listo para compartir: ${invoices.size} facturas, ${products.size} productos, ${incomes.size} ingresos",
                                sharedFile = csvFile
                            )
                        )
                    }
                } catch (e: android.content.ActivityNotFoundException) {
                    _uiState.update {
                        it.copy(
                            isExporting = false,
                            exportResult = BackupResult(
                                success = false,
                                message = "No hay ninguna app disponible para compartir el backup. Instala una app de email o mensajería."
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportResult = BackupResult(
                            success = false,
                            message = "Error al crear backup: ${e.message}"
                        )
                    )
                }
            }
        }
    }

    fun clearExportResult() {
        _uiState.update { it.copy(exportResult = null) }
    }
}
