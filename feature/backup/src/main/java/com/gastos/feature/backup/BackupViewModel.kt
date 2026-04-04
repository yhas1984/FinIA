package com.gastos.feature.backup

import android.content.Context
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.Product
import com.gastos.repository.IncomeRepository
import com.gastos.repository.InvoiceRepository
import com.gastos.repository.ProductRepository
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
    val isLoading: Boolean = false,
    val isExporting: Boolean = false,
    val backupResult: BackupResult? = null,
    val exportResult: BackupResult? = null,
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
    private val invoiceRepository: InvoiceRepository,
    private val incomeRepository: IncomeRepository,
    private val productRepository: ProductRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    init {
        checkSignInStatus()
        loadLocalBackups()
    }

    private fun checkSignInStatus() {
        _uiState.update {
            it.copy(
                isSignedIn = backupService.isSignedIn(),
                email = backupService.getSignedInEmail()
            )
        }
    }

    private fun loadLocalBackups() {
        val backups = backupService.getLocalBackups()
        _uiState.update { it.copy(localBackups = backups) }
    }

    fun signIn() {
        _uiState.update {
            it.copy(error = "Google Sign-In requiere configuración adicional")
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

    private fun buildStructuredCsv(invoices: List<Invoice>, incomes: List<Income>, products: List<Product>): String {
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val invoiceById = invoices.associateBy { it.id }
        return buildString {
            append("GASTOS\n")
            append("Fecha,Proveedor,CIF,Base Imponible,IVA importe,Total,Categoria,ID\n")
            invoices.filter { it.tipo == InvoiceType.GASTO }.forEach { inv ->
                append(inv.fecha.let { df.format(Date(it)) })
                append(",")
                append("\"${inv.proveedor.replace("\"", "\"\"")}\"")
                append(",")
                append("\"${(inv.nifEmisor ?: "").replace("\"", "\"\"")}\"")
                append(",")
                append(inv.baseImponible)
                append(",")
                append(inv.ivaImporte)
                append(",")
                append(inv.total)
                append(",")
                append("\"${(inv.categoria ?: "").replace("\"", "\"\"")}\"")
                append(",")
                append(inv.id)
                append("\n")
            }
            append("\nPRODUCTOS\n")
            append("Fecha,Comercio,Producto,Cantidad,Precio unitario,Total,ID producto,ID factura\n")
            products.forEach { p ->
                val inv = invoiceById[p.invoiceId]
                val f = p.fechaCompra ?: inv?.fecha ?: p.createdAt
                append(df.format(Date(f)))
                append(",")
                append("\"${(p.comercio ?: inv?.proveedor ?: "").replace("\"", "\"\"")}\"")
                append(",")
                append("\"${p.descripcion.replace("\"", "\"\"")}\"")
                append(",")
                append(p.cantidad)
                append(",")
                append(p.precioUnitario)
                append(",")
                append(p.subtotal)
                append(",")
                append(p.id)
                append(",")
                append(p.invoiceId)
                append("\n")
            }
            append("\nINGRESOS\n")
            append("Fecha,Concepto,Tipo,Total devengado,Total deducciones,Total líquido,ID\n")
            incomes.forEach { income ->
                append(df.format(Date(income.fecha)))
                append(",")
                append("\"${income.concepto.replace("\"", "\"\"")}\"")
                append(",")
                append("\"${(income.tipoIngreso ?: "").replace("\"", "\"\"")}\"")
                append(",")
                append(income.totalDevengado)
                append(",")
                append(income.totalDeducciones)
                append(",")
                append(income.totalNeto.takeIf { it > 0 } ?: income.monto)
                append(",")
                append(income.id)
                append("\n")
            }
        }
    }

    fun exportToCsv(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, exportResult = null) }

            try {
                val (invoices, incomes, products) = loadData()

                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    val csvContent = buildString {
                        append(buildStructuredCsv(invoices, incomes, products))
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

                csvFile.writeText(buildStructuredCsv(invoices, incomes, products))

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
