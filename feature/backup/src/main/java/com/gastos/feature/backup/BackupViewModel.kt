@file:Suppress("DEPRECATION")

package com.gastos.feature.backup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Intent
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.ConversionSummary
import com.gastos.domain.model.moneyRecord
import com.gastos.domain.model.summarize
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
import kotlinx.coroutines.ensureActive
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject


data class BackupUiState(
    val reportProgress: Float = 0f,
    val cloudListError: String? = null,
    val sheetsError: String? = null,
    val sheetsSyncError: String? = null,
    val sheetsPending: Int = 0,
    val sheetsFailed: Int = 0,
    val sheetsSynced: Boolean = false,
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
    private val currencyPreference: CurrencyPreference,
    private val reportWriter: FinancialReportWriter,
    private val snapshots: com.gastos.repository.BackupDataRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()
    private var observedCloudSuccessAt: Long? = cloudBackupPreferences.status().lastSuccessAt

    init {
        viewModelScope.launch {
            sheetsSyncManager.operations.collect { operations ->
                val sheets = sheetsSyncManager.currentOperations(operations)
                _uiState.update { it.copy(sheetsPending = sheets.count { row -> row.status != RemoteSyncStatus.FAILED },
                    sheetsFailed = sheets.count { row -> row.status == RemoteSyncStatus.FAILED },
                    sheetsSyncError = sheetsErrorMessage(context, sheets.firstNotNullOfOrNull { row -> row.lastError })) }
            }
        }
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
                hasSheetLink = account?.let(sheetsSyncManager::isEnabled) == true,
                sheetsUrl = account?.let(sheetsSyncManager::getStoredId)?.takeIf(String::isNotBlank)?.let { id -> "https://docs.google.com/spreadsheets/d/$id/edit" }
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
                    sheetsUrl = sheetsSyncManager.getStoredId(account).takeIf(String::isNotBlank)?.let { "https://docs.google.com/spreadsheets/d/$it/edit" },
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
    fun exportToSheets(rebuild: Boolean = false) {
        if (_uiState.value.isExportingSheets) return
        _uiState.update { it.copy(isExportingSheets = true, sheetsError = null, sheetsSynced = false) }
        viewModelScope.launch {
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
                // Reutiliza el sheet existente si ya había uno vinculado.
                val existingId = sheetsSyncManager.getStoredId(account)
                if (existingId.isNotBlank() && !rebuild) {
                    val remaining = sheetsSyncManager.syncChanges()
                    _uiState.update { it.copy(isExportingSheets = false, hasSheetLink = true,
                        sheetsSynced = remaining == 0,
                        sheetsUrl = "https://docs.google.com/spreadsheets/d/$existingId/edit") }
                    return@launch
                }
                val (invoices, incomes, products) = loadData()
                val (url, spreadsheetId) = sheetsExportService.exportToSheets(
                    account, invoices, incomes, products, existingId
                )
                sheetsSyncManager.setSpreadsheetId(account, spreadsheetId)
                val remaining = sheetsSyncManager.syncChanges()
                _uiState.update {
                    it.copy(isExportingSheets = false, sheetsUrl = url, hasSheetLink = true, sheetsSynced = remaining == 0)
                }
            } catch (cancelled: CancellationException) {
                _uiState.update { it.copy(isExportingSheets = false) }
                throw cancelled
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isExportingSheets = false,
                        sheetsError = sheetsErrorMessage(context, sheetsFailureCode(e))
                    )
                }
            }
        }
    }

    fun clearSheetsResult() { _uiState.update { it.copy(sheetsError = null) } }
    fun rebuildSheets() = exportToSheets(rebuild = true)
    fun takeOverSheets() {
        if (_uiState.value.isExportingSheets) return
        _uiState.update { it.copy(isExportingSheets = true, sheetsError = null, sheetsSynced = false) }
        viewModelScope.launch {
            try {
                val account = requireNotNull(sheetsExportService.getLastSignedInAccount())
                sheetsExportService.takeOverWriter(account, sheetsSyncManager.getStoredId(account))
                val remaining = sheetsSyncManager.syncChanges()
                _uiState.update { it.copy(sheetsSynced = remaining == 0) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _uiState.update { it.copy(sheetsError = sheetsErrorMessage(context, sheetsFailureCode(error))) } }
            finally { _uiState.update { it.copy(isExportingSheets = false) } }
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

    fun exportEncryptedBackup(context: Context, uri: Uri, mode: BackupMode = BackupMode.DATA_ONLY) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, backupResult = null, error = null) }
            try {
                check(backupArchiveService.isPasswordConfigured()) {
                    "Configura una contraseña de recuperación primero."
                }
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error(context.getString(R.string.destination_open_error))
                val preview = output.use { backupArchiveService.createArchive(it, mode) }
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
        if (_uiState.value.isCloudLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCloudLoading = true, error = null, cloudListError = null) }
            try {
                val backup = cloudBackupService.createBackup()
                cloudBackupPreferences.recordSuccess()
                _uiState.update { it.copy(cloudBackupStatus = cloudBackupPreferences.status(),
                    backupResult = BackupResult(true, context.getString(R.string.drive_backup_saved, backup.name))) }
            } catch (cancelled: CancellationException) {
                _uiState.update { it.copy(isCloudLoading = false) }
                throw cancelled
            } catch (error: Exception) {
                cloudBackupPreferences.recordError(error.message ?: context.getString(R.string.drive_backup_create_failed))
                _uiState.update { it.copy(isCloudLoading = false, cloudBackupStatus = cloudBackupPreferences.status(), error = error.message) }
                return@launch
            }
            refreshCloudList()
        }
    }

    private suspend fun refreshCloudList() {
        try {
            val backups = cloudBackupService.listBackups()
            _uiState.update { it.copy(cloudBackups = backups, cloudListError = null) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { _uiState.update { it.copy(cloudListError = context.getString(R.string.cloud_list_error)) } }
        finally { _uiState.update { it.copy(isCloudLoading = false) } }
    }

    fun loadCloudBackups() {
        if (!premiumStatus.isPremium.value || !sheetsExportService.isSignedIn() || _uiState.value.isCloudLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCloudLoading = true, cloudListError = null) }
            refreshCloudList()
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
        val data = snapshots.financialSnapshot()
        return Triple(data.invoices, data.incomes, data.products)
    }

    private var reportJob: kotlinx.coroutines.Job? = null

    fun exportToCsv(context: Context, uri: Uri) = exportReport(context, uri, ReportFormat.CSV)
    fun exportToPdf(context: Context, uri: Uri) = exportReport(context, uri, ReportFormat.PDF)
    fun shareReport(context: Context, format: ReportFormat) = exportReport(context, null, format)
    fun cancelReport() { reportJob?.cancel() }

    private fun exportReport(context: Context, destination: Uri?, format: ReportFormat) {
        if (_uiState.value.isExporting) return
        _uiState.update { it.copy(isExporting = true, exportResult = null, reportProgress = 0f) }
        reportJob = viewModelScope.launch {
            var temporary: File? = null
            try {
                val file = reportWriter.generate(format) { progress -> _uiState.update { it.copy(reportProgress = progress) } }
                temporary = file
                if (destination != null) kotlinx.coroutines.withContext(Dispatchers.IO) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    // Finish the final copy once it starts; cancellation only discards generated temporary data.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        context.contentResolver.openOutputStream(destination, "wt").use { output ->
                            requireNotNull(output) { context.getString(R.string.destination_open_error) }
                            file.inputStream().use { it.copyTo(output) }
                        }
                    }
                } else {
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = format.mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = android.content.ClipData.newRawUri("FinAI report", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                _uiState.update { it.copy(exportResult = BackupResult(true, context.getString(R.string.report_ready))) }
            } catch (cancelled: CancellationException) {
                _uiState.update { it.copy(exportResult = null) }
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(exportResult = BackupResult(false, context.getString(R.string.share_error, error.message.orEmpty()))) }
            } finally {
                if (destination != null || !(_uiState.value.exportResult?.success ?: false)) temporary?.delete()
                _uiState.update { it.copy(isExporting = false) }
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
