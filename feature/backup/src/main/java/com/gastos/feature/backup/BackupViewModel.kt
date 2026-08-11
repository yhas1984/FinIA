@file:Suppress("DEPRECATION")

package com.gastos.feature.backup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

private fun StringBuilder.appendCsvRow(vararg values: Any?) {
    append(values.joinToString(",") { value ->
        val raw = value?.toString().orEmpty()
        val safe = if (value is String && raw.firstOrNull() in setOf('=', '+', '-', '@')) {
            "'$raw"
        } else {
            raw
        }
        "\"${safe.replace("\"", "\"\"")}\""
    })
    append('\n')
}

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
    val isBackupKeyConfigured: Boolean = false,
    val cloudBackupStatus: CloudBackupStatus = CloudBackupStatus(false, null, null),
    val cloudBackups: List<CloudBackupInfo> = emptyList(),
    val isCloudLoading: Boolean = false,
    val pendingRestore: PendingBackupRestore? = null,
    val restoreState: BackupRestoreState = BackupRestoreState.Idle,
    val error: String? = null
) {
    val isRestoring: Boolean
        get() = restoreState is BackupRestoreState.Running
}

sealed interface PendingBackupRestore {
    val preview: BackupPreview

    data class Manual(val uri: Uri, override val preview: BackupPreview) : PendingBackupRestore
    data class Cloud(val backup: CloudBackupInfo) : PendingBackupRestore {
        override val preview: BackupPreview = backup.preview
    }
}

data class BackupResult(
    val success: Boolean,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val sharedFile: File? = null
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupArchiveService: BackupArchiveService,
    private val cloudBackupService: CloudBackupService,
    private val cloudBackupScheduler: CloudBackupScheduler,
    private val cloudBackupPreferences: CloudBackupPreferences,
    private val restoreCoordinator: BackupRestoreCoordinator,
    private val sheetsExportService: SheetsExportService,
    private val sheetsSyncManager: SheetsSyncManager,
    private val invoiceDriveService: InvoiceDriveService,
    private val invoiceRepository: InvoiceRepository,
    private val incomeRepository: IncomeRepository,
    private val productRepository: ProductRepository,
    private val premiumStatus: PremiumStatusProvider,
    private val exchangeRateProvider: ExchangeRateProvider,
    private val currencyPreference: CurrencyPreference
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()
    private var observedCloudSuccessAt: Long? = cloudBackupPreferences.status().lastSuccessAt

    /** Convierte un importe a la moneda por defecto del usuario (para totales). */
    private fun converted(amount: Double, currency: String): Double =
        exchangeRateProvider.convert(amount, currency, currencyPreference.defaultCurrency.value) ?: 0.0

    init {
        checkSignInStatus()
        refreshBackupState()
        // Observa el estado Premium para habilitar/ocultar la sección Sheets.
        viewModelScope.launch {
            premiumStatus.isPremium.collect { premium ->
                _uiState.update { it.copy(isPremium = premium) }
                if (premium && sheetsExportService.isSignedIn()) loadCloudBackups()
            }
        }
        viewModelScope.launch {
            restoreCoordinator.state.collect { restoreState ->
                _uiState.update { it.copy(restoreState = restoreState) }
            }
        }
        viewModelScope.launch {
            cloudBackupPreferences.statusFlow.collect { status ->
                val hasNewBackup = status.lastSuccessAt != null &&
                    status.lastSuccessAt != observedCloudSuccessAt
                observedCloudSuccessAt = status.lastSuccessAt
                _uiState.update { it.copy(cloudBackupStatus = status) }
                if (
                    hasNewBackup &&
                    !_uiState.value.isCloudLoading &&
                    premiumStatus.isPremium.value &&
                    sheetsExportService.isSignedIn()
                ) {
                    loadCloudBackups()
                }
            }
        }
    }

    private fun checkSignInStatus() {
        val account = sheetsExportService.getLastSignedInAccount()
        _uiState.update {
            it.copy(
                isSignedIn = sheetsExportService.isSignedIn(),
                email = account?.email,
                hasSheetLink = account?.let(sheetsSyncManager::isEnabled) == true
            )
        }
    }

    private fun refreshBackupState() {
        _uiState.update {
            it.copy(
                isBackupKeyConfigured = backupArchiveService.isPasswordConfigured(),
                cloudBackupStatus = cloudBackupPreferences.status()
            )
        }
    }

    /** Devuelve el Intent para lanzar el flujo de Sign-In de Google con scope Sheets. */
    fun getSignInIntent(): Intent = sheetsExportService.getSignInIntent()

    /** Procesa el resultado del Sign-In (desde StartActivityForResult). */
    fun handleSignInResult(data: Intent?) {
        if (data == null) {
            _uiState.update { it.copy(error = context.getString(R.string.google_sign_in_cancelled)) }
            return
        }
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account: GoogleSignInAccount = task.getResult(ApiException::class.java)
            _uiState.update {
                it.copy(
                    isSignedIn = true,
                    email = account.email,
                    hasSheetLink = sheetsSyncManager.isEnabled(account),
                    error = null
                )
            }
            if (premiumStatus.isPremium.value) loadCloudBackups()
        } catch (e: ApiException) {
            _uiState.update {
                it.copy(error = context.getString(R.string.google_sign_in_error, e.statusCode, e.message ?: context.getString(R.string.no_details)))
            }
        }
    }

    /**
     * Exporta los datos al Google Sheet vinculado o crea uno si no existe.
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
                            error = context.getString(R.string.google_sign_in_required)
                        )
                    }
                    return@launch
                }
                val (invoices, incomes, products) = loadData()
                // Reutiliza el sheet existente si ya había uno vinculado.
                val existingId = sheetsSyncManager.getStoredId(account)
                val (url, spreadsheetId) = sheetsExportService.exportToSheets(
                    account, invoices, incomes, products, existingId
                )
                sheetsSyncManager.setSpreadsheetId(account, spreadsheetId)
                _uiState.update {
                    it.copy(isExportingSheets = false, sheetsUrl = url, hasSheetLink = true)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isExportingSheets = false,
                        error = context.getString(R.string.sheets_export_error, e.message.orEmpty())
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
                if (account == null) {
                    _uiState.update {
                        it.copy(isExportingSheets = false, error = context.getString(R.string.no_sheet_linked_export_first))
                    }
                    return@launch
                }
                val existingId = sheetsSyncManager.getStoredId(account)
                if (existingId.isBlank()) {
                    _uiState.update {
                        it.copy(isExportingSheets = false, error = context.getString(R.string.no_sheet_linked_export_first))
                    }
                    return@launch
                }
                val (invoices, incomes, products) = loadData()
                val (url, spreadsheetId) = sheetsExportService.exportToSheets(
                    account, invoices, incomes, products, existingId
                )
                sheetsSyncManager.setSpreadsheetId(account, spreadsheetId)
                _uiState.update { it.copy(isExportingSheets = false, sheetsUrl = url) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isExportingSheets = false,
                        error = context.getString(R.string.sheets_sync_error, e.message.orEmpty())
                    )
                }
            }
        }
    }

    fun configureBackupPassword(password: String, confirmation: String) {
        if (password.length < 8) {
            _uiState.update { it.copy(error = "La contraseña debe tener al menos 8 caracteres.") }
            return
        }
        if (password != confirmation) {
            _uiState.update { it.copy(error = "Las contraseñas no coinciden.") }
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            val chars = password.toCharArray()
            try {
                backupArchiveService.configurePassword(chars)
                _uiState.update {
                    it.copy(
                        isBackupKeyConfigured = true,
                        backupResult = BackupResult(true, context.getString(R.string.backup_password_configured)),
                        error = null
                    )
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(error = error.message ?: context.getString(R.string.backup_password_configure_failed))
                }
            } finally {
                chars.fill('\u0000')
            }
        }
    }

    fun exportEncryptedBackup(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, backupResult = null, error = null) }
            try {
                check(backupArchiveService.isPasswordConfigured()) {
                    "Configura una contraseña de recuperación primero."
                }
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error(context.getString(R.string.destination_open_error))
                val preview = output.use { backupArchiveService.createArchive(it) }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        backupResult = BackupResult(
                            success = true,
                            message = context.getString(
                                R.string.encrypted_backup_created,
                                preview.invoiceCount,
                                preview.productCount,
                                preview.incomeCount
                            )
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: context.getString(R.string.backup_create_failed))
                }
            }
        }
    }

    fun inspectManualBackup(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: error(context.getString(R.string.backup_open_selected_failed))
                val preview = input.use(backupArchiveService::inspect)
                _uiState.update {
                    it.copy(pendingRestore = PendingBackupRestore.Manual(uri, preview), error = null)
                }
            } catch (error: Exception) {
                _uiState.update { it.copy(error = error.message ?: context.getString(R.string.backup_invalid)) }
            }
        }
    }

    fun requestCloudRestore(backup: CloudBackupInfo) {
        _uiState.update { it.copy(pendingRestore = PendingBackupRestore.Cloud(backup), error = null) }
    }

    fun dismissRestore() {
        if (restoreCoordinator.isRunning()) return
        _uiState.update { it.copy(pendingRestore = null) }
    }

    fun restorePendingBackup(context: Context, password: String) {
        val pending = _uiState.value.pendingRestore ?: return
        val chars = password.toCharArray()
        val sourceLabel = when (pending) {
            is PendingBackupRestore.Manual -> context.getString(R.string.backup_file_label)
            is PendingBackupRestore.Cloud -> pending.backup.name
        }
        val started = restoreCoordinator.start(viewModelScope, sourceLabel) {
            _uiState.update { it.copy(error = null) }
            var downloaded: File? = null
            try {
                restoreCoordinator.updateStage(context.getString(R.string.restore_stage_opening))
                val input = when (pending) {
                    is PendingBackupRestore.Manual -> context.contentResolver.openInputStream(pending.uri)
                        ?: error(context.getString(R.string.backup_open_selected_failed))
                    is PendingBackupRestore.Cloud -> {
                        restoreCoordinator.updateStage(context.getString(R.string.restore_stage_downloading))
                        downloaded = cloudBackupService.downloadBackup(pending.backup.fileId)
                        requireNotNull(downloaded).inputStream()
                    }
                }
                restoreCoordinator.updateStage(context.getString(R.string.restore_stage_restoring))
                val result = input.use {
                    backupArchiveService.restore(it, chars, restoreCoordinator::beginCommit)
                }
                refreshBackupState()
                _uiState.update {
                    it.copy(
                        pendingRestore = null,
                        backupResult = BackupResult(
                            true,
                            context.getString(
                                R.string.restore_completed,
                                result.preview.invoiceCount,
                                result.preview.productCount,
                                result.preview.incomeCount,
                                result.restoredImages
                            )
                        )
                    )
                }
            } catch (error: CancellationException) {
                _uiState.update { it.copy(error = context.getString(R.string.restore_cancelled)) }
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(error = error.message ?: context.getString(R.string.restore_failed))
                }
            } finally {
                chars.fill('\u0000')
                downloaded?.delete()
            }
        }
        if (!started) {
            chars.fill('\u0000')
            _uiState.update { it.copy(error = context.getString(R.string.restore_in_progress)) }
        }
    }

    fun cancelRestore() {
        restoreCoordinator.cancel()
    }

    fun setAutomaticCloudBackup(enabled: Boolean) {
        if (enabled) {
            when {
                !_uiState.value.isPremium -> {
                    _uiState.update { it.copy(error = context.getString(R.string.auto_backup_requires_premium)) }
                    return
                }
                !_uiState.value.isSignedIn -> {
                    _uiState.update { it.copy(error = context.getString(R.string.connect_google_first)) }
                    return
                }
                !backupArchiveService.isPasswordConfigured() -> {
                    _uiState.update { it.copy(error = context.getString(R.string.configure_recovery_password_before_auto_backup)) }
                    return
                }
            }
        }
        cloudBackupScheduler.setEnabled(enabled)
        refreshBackupState()
    }

    fun createCloudBackupNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCloudLoading = true, error = null) }
            try {
                val backup = cloudBackupService.createBackup()
                cloudBackupPreferences.recordSuccess()
                val backups = cloudBackupService.listBackups()
                _uiState.update {
                    it.copy(
                        isCloudLoading = false,
                        cloudBackups = backups,
                        cloudBackupStatus = cloudBackupPreferences.status(),
                        backupResult = BackupResult(true, context.getString(R.string.drive_backup_saved, backup.name))
                    )
                }
            } catch (error: Exception) {
                cloudBackupPreferences.recordError(error.message ?: context.getString(R.string.drive_backup_create_failed))
                _uiState.update {
                    it.copy(isCloudLoading = false, cloudBackupStatus = cloudBackupPreferences.status(), error = error.message ?: context.getString(R.string.drive_backup_create_failed))
                }
            }
        }
    }

    fun loadCloudBackups() {
        if (!premiumStatus.isPremium.value || !sheetsExportService.isSignedIn()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCloudLoading = true) }
            try {
                val backups = cloudBackupService.listBackups()
                _uiState.update {
                    it.copy(
                        isCloudLoading = false,
                        cloudBackups = backups,
                        cloudBackupStatus = cloudBackupPreferences.status()
                    )
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isCloudLoading = false, error = error.message ?: context.getString(R.string.drive_backup_load_failed))
                }
            }
        }
    }

    fun deleteCloudBackups() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCloudLoading = true, error = null) }
            try {
                val deleted = cloudBackupService.deleteAllBackups()
                _uiState.update {
                    it.copy(
                        isCloudLoading = false,
                        cloudBackups = emptyList(),
                        backupResult = BackupResult(true, context.getString(R.string.drive_backups_deleted, deleted))
                    )
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(isCloudLoading = false, error = error.message ?: context.getString(R.string.drive_backups_delete_failed))
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

    private fun buildCsvContent(
        invoices: List<Invoice>,
        incomes: List<Income>,
        products: List<Product>
    ): String = buildString {
        append('\uFEFF')
        appendCsvRow(
            context.getString(R.string.csv_header_type),
            context.getString(R.string.csv_header_id),
            context.getString(R.string.csv_header_date),
            context.getString(R.string.csv_header_invoice_number),
            context.getString(R.string.csv_header_concept),
            context.getString(R.string.csv_header_amount),
            context.getString(R.string.csv_header_currency),
            context.getString(R.string.csv_header_tax_base),
            context.getString(R.string.csv_header_vat_percent),
            context.getString(R.string.csv_header_vat_amount),
            context.getString(R.string.csv_header_irpf_percent),
            context.getString(R.string.csv_header_gross),
            context.getString(R.string.csv_header_net),
            context.getString(R.string.csv_header_category),
            context.getString(R.string.csv_header_subcategory),
            context.getString(R.string.csv_header_notes)
        )
        val invoiceById = invoices.associateBy { it.id }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)

        invoices.forEach { invoice ->
            appendCsvRow(
                 if (invoice.tipo == InvoiceType.GASTO) context.getString(R.string.csv_type_expense) else context.getString(R.string.csv_type_income),
                invoice.id,
                dateFormat.format(Date(invoice.fecha)),
                invoice.numeroFactura.orEmpty(),
                invoice.proveedor,
                invoice.total,
                invoice.moneda,
                invoice.baseImponible ?: "",
                invoice.ivaPercent,
                invoice.cuotaIva ?: "",
                invoice.irpfPercent,
                "",
                "",
                invoice.categoria.orEmpty(),
                invoice.subcategoria.orEmpty(),
                invoice.notas.orEmpty()
            )
        }
        products.forEach { product ->
            appendCsvRow(
                 context.getString(R.string.csv_type_product),
                product.id,
                dateFormat.format(Date(product.createdAt)),
                "",
                product.descripcion,
                product.subtotal,
                invoiceById[product.invoiceId]?.moneda.orEmpty(),
                "",
                product.ivaPercent,
                "",
                0,
                "",
                "",
                "",
                "",
                ""
            )
        }
        incomes.forEach { income ->
            appendCsvRow(
                 context.getString(R.string.csv_type_income),
                income.id,
                dateFormat.format(Date(income.fecha)),
                "",
                income.concepto,
                income.monto,
                income.moneda,
                "",
                income.ivaPercent,
                "",
                income.irpfPercent,
                income.totalDevengado,
                income.totalNeto,
                income.categoria.orEmpty(),
                income.subcategoria.orEmpty(),
                income.notas.orEmpty()
            )
        }

        val target = currencyPreference.defaultCurrency.value
        val totalGastos = invoices.filter { it.tipo == InvoiceType.GASTO }
            .sumOf { converted(it.total, it.moneda) }
        val totalIngresos = incomes.sumOf { converted(it.monto, it.moneda) } +
            invoices.filter { it.tipo == InvoiceType.INGRESO }
                .sumOf { converted(it.total, it.moneda) }
        append('\n')
        appendCsvRow(context.getString(R.string.csv_summary), context.getString(R.string.csv_summary_currency), target)
        appendCsvRow(context.getString(R.string.csv_summary_expenses), totalGastos)
        appendCsvRow(context.getString(R.string.csv_summary_income), totalIngresos)
        appendCsvRow(context.getString(R.string.csv_summary_balance), totalIngresos - totalGastos)
        appendCsvRow(context.getString(R.string.csv_summary_exported_at), SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date()))
    }

    fun exportToCsv(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, exportResult = null) }

            try {
                val (invoices, incomes, products) = loadData()

                val outputStream = context.contentResolver.openOutputStream(uri)
                    ?: error(context.getString(R.string.destination_open_error))
                outputStream.use {
                    it.write(buildCsvContent(invoices, incomes, products).toByteArray(Charsets.UTF_8))
                }

                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportResult = BackupResult(
                            success = true,
                            message = context.getString(
                                R.string.csv_exported,
                                invoices.size,
                                products.size,
                                incomes.size
                            )
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportResult = BackupResult(
                            success = false,
                            message = context.getString(R.string.csv_export_error, e.message.orEmpty())
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
                val df = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
                val targetCurrency = currencyPreference.defaultCurrency.value

                val pdfDocument = PdfDocument()
                try {
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
                canvas.drawText(context.getString(R.string.pdf_title), 40f, y, titlePaint)
                y += 30f
                canvas.drawText(
                    context.getString(
                        R.string.pdf_generated,
                         SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ROOT).format(Date())
                    ),
                    40f,
                    y,
                    bodyPaint
                )
                y += 30f

                // Summary (convertido a la moneda por defecto del usuario)
                val totalGastos = invoices.filter { it.tipo == InvoiceType.GASTO }.sumOf { converted(it.total, it.moneda) }
                val totalIngresos = incomes.sumOf { converted(it.monto, it.moneda) } + invoices.filter { it.tipo == InvoiceType.INGRESO }.sumOf { converted(it.total, it.moneda) }

                canvas.drawText(context.getString(R.string.pdf_summary), 40f, y, headerPaint)
                y += 20f
                canvas.drawText(
                    context.getString(
                        R.string.pdf_total_expenses,
                        com.gastos.domain.model.formatMoney(totalGastos, targetCurrency)
                    ),
                    60f,
                    y,
                    bodyPaint
                )
                y += 18f
                canvas.drawText(
                    context.getString(
                        R.string.pdf_total_income,
                        com.gastos.domain.model.formatMoney(totalIngresos, targetCurrency)
                    ),
                    60f,
                    y,
                    bodyPaint
                )
                y += 18f
                canvas.drawText(
                    context.getString(
                        R.string.pdf_balance,
                        com.gastos.domain.model.formatMoney(totalIngresos - totalGastos, targetCurrency)
                    ),
                    60f,
                    y,
                    bodyPaint
                )
                y += 30f

                // Gastos
                canvas.drawText(context.getString(R.string.pdf_expenses), 40f, y, headerPaint)
                y += 20f
                invoices.filter { it.tipo == InvoiceType.GASTO }.forEach { inv ->
                    if (y > 780f) {
                        pdfDocument.finishPage(page)
                        page = pdfDocument.startPage(pageInfo)
                        canvas = page.canvas
                        y = 50f
                    }
                    canvas.drawText("${df.format(Date(inv.fecha))} - ${inv.proveedor}: ${com.gastos.domain.model.formatMoney(inv.total, inv.moneda)}", 60f, y, bodyPaint)
                    y += 16f
                }
                y += 15f

                // Ingresos
                canvas.drawText(context.getString(R.string.pdf_income), 40f, y, headerPaint)
                y += 20f
                incomes.forEach { inc ->
                    if (y > 780f) {
                        pdfDocument.finishPage(page)
                        page = pdfDocument.startPage(pageInfo)
                        canvas = page.canvas
                        y = 50f
                    }
                    canvas.drawText("${df.format(Date(inc.fecha))} - ${inc.concepto}: ${com.gastos.domain.model.formatMoney(inc.monto, inc.moneda)}", 60f, y, bodyPaint)
                    y += 16f
                }

                pdfDocument.finishPage(page)

                val outputStream = context.contentResolver.openOutputStream(uri)
                    ?: error(context.getString(R.string.destination_open_error))
                outputStream.use { pdfDocument.writeTo(it) }
                } finally {
                    pdfDocument.close()
                }

                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportResult = BackupResult(
                            success = true,
                            message = context.getString(R.string.pdf_exported)
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportResult = BackupResult(
                            success = false,
                            message = context.getString(R.string.pdf_export_error, e.message.orEmpty())
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

                csvFile.writeText(buildCsvContent(invoices, incomes, products), Charsets.UTF_8)

                val fileUri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    csvFile
                )

                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(android.content.Intent.EXTRA_STREAM, fileUri)
                     putExtra(android.content.Intent.EXTRA_SUBJECT, context.getString(R.string.share_subject, timestamp))
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                 val chooser = android.content.Intent.createChooser(
                     shareIntent,
                     context.getString(R.string.share_chooser)
                 )
                chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)

                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportResult = BackupResult(
                            success = true,
                            message = context.getString(
                                R.string.share_ready,
                                invoices.size,
                                products.size,
                                incomes.size
                            ),
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
                            message = context.getString(R.string.share_error, e.message.orEmpty())
                        )
                    )
                }
            }
        }
    }

    fun clearExportResult() {
        _uiState.update { it.copy(exportResult = null) }
    }

    /**
     * Cierra sesión de Google (desvincula la cuenta de esta app) y limpia
     * el sheetId guardado. Tras esto, la pantalla vuelve al estado "no
     * conectado" y se puede elegir otra cuenta.
     */
    fun signOut() {
        viewModelScope.launch {
            sheetsExportService.signOut()
            cloudBackupScheduler.setEnabled(false)
            invoiceDriveService.clearAccountCache()
            _uiState.update {
                it.copy(
                    isSignedIn = false,
                    email = null,
                    hasSheetLink = false,
                    sheetsUrl = null,
                    cloudBackups = emptyList(),
                    cloudBackupStatus = cloudBackupPreferences.status()
                )
            }
        }
    }
}
