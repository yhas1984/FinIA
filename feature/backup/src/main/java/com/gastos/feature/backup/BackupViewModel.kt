package com.gastos.feature.backup

import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.Product
import com.gastos.repository.CurrencyPreference
import com.gastos.repository.ExchangeRateProvider
import com.gastos.repository.IncomeRepository
import com.gastos.repository.InvoiceRepository
import com.gastos.repository.PremiumStatusProvider
import com.gastos.repository.ProductRepository
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val isPremium: Boolean = false,
    val isLoading: Boolean = false,
    val isExporting: Boolean = false,
    val isExportingSheets: Boolean = false,
    val backupResult: BackupResult? = null,
    val exportResult: BackupResult? = null,
    val sheetsUrl: String? = null,
    val localBackups: List<File> = emptyList(),
    val error: String? = null
)

data class BackupResult(
    val success: Boolean,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val sharedFile: File? = null
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupService: BackupService,
    private val sheetsExportService: SheetsExportService,
    private val sheetsSyncManager: SheetsSyncManager,
    private val invoiceRepository: InvoiceRepository,
    private val incomeRepository: IncomeRepository,
    private val productRepository: ProductRepository,
    private val premiumStatus: PremiumStatusProvider,
    private val exchangeRateProvider: ExchangeRateProvider,
    private val currencyPreference: CurrencyPreference
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    /** Convierte un importe a la moneda por defecto del usuario (para totales). */
    private fun converted(amount: Double, currency: String): Double =
        exchangeRateProvider.convert(amount, currency, currencyPreference.defaultCurrency.value) ?: 0.0

    init {
        checkSignInStatus()
        loadLocalBackups()
        // Observa el estado Premium para habilitar/ocultar la sección Sheets.
        viewModelScope.launch {
            premiumStatus.isPremium.collect { premium ->
                _uiState.update { it.copy(isPremium = premium) }
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

    private fun loadLocalBackups() {
        val backups = backupService.getLocalBackups()
        _uiState.update { it.copy(localBackups = backups) }
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
     * El resultado (URL del sheet) se expone en [BackupUiState.sheetsUrl].
     */
    fun exportToSheets() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isExportingSheets = true, sheetsUrl = null, error = null)
            }
            try {
                val account = sheetsExportService.getLastSignedInAccount()
                if (account == null || !sheetsExportService.isSignedIn()) {
                    _uiState.update {
                        it.copy(
                            isExportingSheets = false,
                            error = "Debes iniciar sesión con Google primero."
                        )
                    }
                    return@launch
                }
                val (invoices, incomes, products) = loadData()
                // Reutiliza el sheet existente si ya había uno vinculado.
                val existingId = sheetsSyncManager.getStoredId()
                val (url, spreadsheetId) = sheetsExportService.exportToSheets(
                    account, invoices, incomes, products, existingId
                )
                // Persistir para que la sincronización en background funcione.
                sheetsSyncManager.setSpreadsheetId(spreadsheetId)
                _uiState.update {
                    it.copy(isExportingSheets = false, sheetsUrl = url)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isExportingSheets = false,
                        error = "Error al exportar a Sheets: ${e.message}"
                    )
                }
            }
        }
    }

    fun clearSheetsResult() {
        _uiState.update { it.copy(sheetsUrl = null, error = null) }
    }

    /** Fuerza la sincronización de todos los datos existentes al sheet vinculado. */
    fun syncAllToSheets() {
        viewModelScope.launch {
            _uiState.update { it.copy(isExportingSheets = true, sheetsUrl = null, error = null) }
            try {
                val account = sheetsExportService.getLastSignedInAccount()
                val existingId = sheetsSyncManager.getStoredId()
                if (account == null || existingId.isBlank()) {
                    _uiState.update {
                        it.copy(isExportingSheets = false, error = "No hay sheet vinculado. Exporta primero.")
                    }
                    return@launch
                }
                val (invoices, incomes, products) = loadData()
                val (url, _) = sheetsExportService.exportToSheets(
                    account, invoices, incomes, products, existingId
                )
                _uiState.update { it.copy(isExportingSheets = false, sheetsUrl = url) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isExportingSheets = false, error = "Error al sincronizar: ${e.message}")
                }
            }
        }
    }

    fun createBackup() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, backupResult = null, error = null) }

            try {
                val result = backupService.createBackup()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        backupResult = result,
                        error = if (!result.success) result.message else null
                    )
                }
                if (result.success) {
                    loadLocalBackups()
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Error al crear backup"
                    )
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
                    append("RESUMEN (en ${currencyPreference.defaultCurrency.value})\n")
                    val totalGastos = invoices.filter { it.tipo == InvoiceType.GASTO }.sumOf { converted(it.total, it.moneda) }
                    val totalIngresos = incomes.sumOf { converted(it.monto, it.moneda) } + invoices.filter { it.tipo == InvoiceType.INGRESO }.sumOf { converted(it.total, it.moneda) }
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
                val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
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

                // Summary (convertido a la moneda por defecto del usuario)
                val totalGastos = invoices.filter { it.tipo == InvoiceType.GASTO }.sumOf { converted(it.total, it.moneda) }
                val totalIngresos = incomes.sumOf { converted(it.monto, it.moneda) } + invoices.filter { it.tipo == InvoiceType.INGRESO }.sumOf { converted(it.total, it.moneda) }

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
                        page = pdfDocument.startPage(pageInfo)
                        canvas = page.canvas
                        y = 50f
                    }
                    canvas.drawText("${df.format(Date(inc.fecha))} - ${inc.concepto}: ${fmt.format(inc.monto)}", 60f, y, bodyPaint)
                    y += 16f
                }

                pdfDocument.finishPage(page)

                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    pdfDocument.writeTo(outputStream)
                }
                pdfDocument.close()

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

    fun restoreFromLocal(file: File) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        backupResult = BackupResult(
                            success = false,
                            message = "Restauración no implementada aún"
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Error al restaurar"
                    )
                }
            }
        }
    }

    fun deleteBackup(file: File) {
        viewModelScope.launch {
            try {
                backupService.deleteBackup(file)
                loadLocalBackups()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = e.message ?: "Error al eliminar backup")
                }
            }
        }
    }

    fun clearExportResult() {
        _uiState.update { it.copy(exportResult = null) }
    }
}
