@file:Suppress("DEPRECATION")

package com.gastos.feature.backup

import android.content.Context
import android.content.Intent
import com.gastos.domain.model.Income
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.domain.model.Product
import com.gastos.extension.SafeLog
import com.gastos.repository.CurrencyPreference
import com.gastos.repository.ExchangeRateProvider
import com.gastos.repository.PremiumStatusProvider
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.AddSheetRequest
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest
import com.google.api.services.sheets.v4.model.CellFormat
import com.google.api.services.sheets.v4.model.Color
import com.google.api.services.sheets.v4.model.DeleteSheetRequest
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
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Google authentication and safe, account-scoped FinAI workbook access. */
@Singleton
class SheetsExportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val premiumStatus: PremiumStatusProvider,
    private val exchangeRateProvider: ExchangeRateProvider,
    private val currencyPreference: CurrencyPreference,
    private val sheetsLinkStore: SheetsLinkStore,
    private val operationCoordinator: SheetsOperationCoordinator
) {
    companion object {
        // drive.file también autoriza la API de Sheets para archivos creados por FinAI.
        private val DRIVE_FILE_SCOPE by lazy { Scope(DriveScopes.DRIVE_FILE) }
        private val DRIVE_APPDATA_SCOPE by lazy { Scope(DriveScopes.DRIVE_APPDATA) }
        private const val SPREADSHEET_MIME_TYPE = "application/vnd.google-apps.spreadsheet"
        private const val FINAI_SHEET_PROPERTY = "finaiSpreadsheet"
        private const val FINAI_SCHEMA_VERSION_PROPERTY = "finaiSchemaVersion"
        private const val FINAI_SCHEMA_LOCALE_PROPERTY = "finaiSchemaLocale"
        private const val FINAI_SHEET_PROPERTY_VALUE = "true"
        private const val LEGACY_SHEET_NAME_PREFIX = "FinAI - Export"
    }

    /** Cliente de Google Sign-In con los scopes de Sheets. */
    fun getSignInClient(): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(DRIVE_FILE_SCOPE, DRIVE_APPDATA_SCOPE)
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    /** Intent para lanzar el flujo de Sign-In con permisos de Sheets. */
    fun getSignInIntent(): Intent = getSignInClient().signInIntent

    /** Cierra la misma sesión/scopes utilizados por el flujo de Sheets. */
    fun signOut() {
        getSignInClient().signOut()
    }

    /** ¿Hay una cuenta Google con permisos de Sheets concedidos? */
    fun isSignedIn(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return account != null &&
            GoogleSignIn.hasPermissions(account, DRIVE_FILE_SCOPE, DRIVE_APPDATA_SCOPE)
    }

    fun getSignedInEmail(): String? =
        GoogleSignIn.getLastSignedInAccount(context)?.email

    /** Devuelve la cuenta autenticada, o null si no hay sesión con permisos. */
    fun getLastSignedInAccount(): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)

    suspend fun exportToSheets(
        account: GoogleSignInAccount,
        invoices: List<Invoice>,
        incomes: List<Income>,
        products: List<Product>,
        existingSpreadsheetId: String = ""
    ): Pair<String, String> = operationCoordinator.mutex.withLock {
        withContext(Dispatchers.IO) {
            check(premiumStatus.isPremium.value) { context.getString(R.string.sheets_export_requires_premium) }
            val (sheets, drive) = services(account)
            val linked = existingSpreadsheetId.ifBlank { sheetsLinkStore.getSpreadsheetId(account) }
            val reusable = if (linked.isNotBlank()) {
                // Permission and network errors must never create a replacement book silently.
                SheetsWorkbookAccess.read(drive, linked)
                SpreadsheetResolution(linked)
            } else findReusableSpreadsheet(drive, "", sheetsLinkStore.getLegacySpreadsheetId(), requireNotNull(account.email))
            val id = reusable?.id ?: SheetsWorkbookCreation(drive).create(sheetsLinkStore.pendingCreation(account)) {
                sheetsLinkStore.recordCreation(account, it)
            }
            // Persist the link before population so an interrupted creation is resumed, not duplicated.
            sheetsLinkStore.setSpreadsheetId(account, id)
            val properties = SheetsWorkbookAccess.read(drive, id).appProperties.orEmpty()
            val version = properties[FINAI_SCHEMA_VERSION_PROPERTY]?.toIntOrNull()
            check(version == null || version <= SheetsSchema.SCHEMA_VERSION) { "SHEETS_NEWER_SCHEMA" }
            check(properties[FINAI_SCHEMA_VERSION_PROPERTY] == null || version != null) { "SHEETS_UNKNOWN_SCHEMA" }
            val locale = if (reusable == null) resolveSchemaLocale() else resolveSpreadsheetLocale(drive, sheets, id) ?: resolveSchemaLocale()
            verifyWriter(drive, id, allowClaim = true)
            if (reusable != null) createVerifiedCopy(sheets, account, id)
            SheetsWorkbookEngine(sheets, id, conversion(locale)) { verifyWriter(drive, id) }.write(invoices, incomes, products,
                full = true, initializeFilters = version != SheetsSchema.SCHEMA_VERSION, initializeWorkbook = properties["finaiInitializing"] == "true")
            markAsFinAiSpreadsheet(drive, id, locale)
            sheetsLinkStore.clearFingerprints(SheetsLinkStore.getAccountPreferenceKey(account.id, account.email), id)
            if (reusable?.adoptedLegacyId == true) sheetsLinkStore.clearLegacySpreadsheetId()
            "https://docs.google.com/spreadsheets/d/$id/edit" to id
        }
    }

    internal suspend fun schemaVersion(account: GoogleSignInAccount, id: String): Int? = withContext(Dispatchers.IO) {
        val raw = SheetsWorkbookAccess.read(services(account).second, id).appProperties?.get(FINAI_SCHEMA_VERSION_PROPERTY)
        check(raw == null || raw.toIntOrNull() != null) { "SHEETS_UNKNOWN_SCHEMA" }
        raw?.toIntOrNull()
    }

    internal suspend fun syncDocument(account: GoogleSignInAccount, id: String, invoices: List<Invoice>, incomes: List<Income>,
        products: List<Product>, deleteUuid: String? = null) = syncDocuments(account, id, invoices, incomes, products, setOfNotNull(deleteUuid))

    internal suspend fun syncDocuments(account: GoogleSignInAccount, id: String, invoices: List<Invoice>, incomes: List<Income>,
        products: List<Product>, deleteUuids: Set<String> = emptySet()) = operationCoordinator.mutex.withLock {
        withContext(Dispatchers.IO) {
            check(premiumStatus.isPremium.value) { "PREMIUM_REQUIRED" }
            check(sheetsLinkStore.getSpreadsheetId(account) == id && getLastSignedInAccount()?.let {
                SheetsLinkStore.getAccountPreferenceKey(it.id, it.email) == SheetsLinkStore.getAccountPreferenceKey(account.id, account.email)
            } == true) { "WRONG_ACCOUNT" }
            val (sheets, drive) = services(account)
            val properties = SheetsWorkbookAccess.read(drive, id).appProperties.orEmpty()
            check(properties[FINAI_SCHEMA_VERSION_PROPERTY]?.toIntOrNull() == SheetsSchema.SCHEMA_VERSION) { "SHEETS_SCHEMA_REQUIRED" }
            verifyWriter(drive, id)
            SheetsWorkbookEngine(sheets, id, conversion(SheetsSchema.localeFromCode(properties[FINAI_SCHEMA_LOCALE_PROPERTY]))) { verifyWriter(drive, id) }
                .write(invoices, incomes, products, deleteUuids = deleteUuids)
        }
    }

    suspend fun takeOverWriter(account: GoogleSignInAccount, id: String) = operationCoordinator.mutex.withLock {
        withContext(Dispatchers.IO) {
            val (_, drive) = services(account)
            val old = SheetsWorkbookAccess.read(drive, id).appProperties.orEmpty()
            check(old[FINAI_SCHEMA_VERSION_PROPERTY]?.toIntOrNull() == SheetsSchema.SCHEMA_VERSION) { "SHEETS_SCHEMA_REQUIRED" }
            drive.files().update(id, DriveFile().setAppProperties(old + ("finaiWriterId" to sheetsLinkStore.writerId()))).execute()
            verifyWriter(drive, id)
        }
    }

    private fun verifyWriter(drive: Drive, id: String, allowClaim: Boolean = false) {
        val old = SheetsWorkbookAccess.read(drive, id).appProperties.orEmpty()
        val writer = old["finaiWriterId"]
        check(writer == sheetsLinkStore.writerId() || writer == null && allowClaim) { "SHEETS_OTHER_DEVICE" }
        if (writer == null) {
            drive.files().update(id, DriveFile().setAppProperties(old + ("finaiWriterId" to sheetsLinkStore.writerId()))).execute()
            check(drive.files().get(id).setFields("appProperties").execute().appProperties?.get("finaiWriterId") == sheetsLinkStore.writerId()) { "SHEETS_OTHER_DEVICE" }
        }
    }

    private fun createVerifiedCopy(sheets: Sheets, account: GoogleSignInAccount, id: String) {
        val source = sheets.spreadsheets().get(id).setIncludeGridData(true).execute()
        try {
            SheetsRecoverySnapshot.save(java.io.File(context.noBackupFilesDir, "sheets-recovery"),
                SheetsLinkStore.getAccountPreferenceKey(account.id, account.email), source)
        } catch (error: java.io.IOException) { throw IllegalStateException("SHEETS_BACKUP_UNVERIFIED", error) }
    }

    private fun services(account: GoogleSignInAccount): Pair<Sheets, Drive> {
        val credential = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE_FILE)).setSelectedAccount(account.account)
        val initializer = com.google.api.client.http.HttpRequestInitializer { request ->
            credential.initialize(request)
            request.connectTimeout = 15_000
            request.readTimeout = 60_000
            request.numberOfRetries = 0 // The durable outbox re-reads remote identities before retrying.
        }
        return Sheets.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), initializer).setApplicationName("FinAI").build() to
            Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), initializer).setApplicationName("FinAI").build()
    }

    private fun conversion(locale: SheetsSchema.LocaleCode) = SheetsSchema.ConversionSnapshot(currencyPreference.defaultCurrency.value, locale, exchangeRateProvider)

    private suspend fun findReusableSpreadsheet(
        drive: Drive,
        linkedSpreadsheetId: String,
        legacySpreadsheetId: String,
        accountEmail: String
    ): SpreadsheetResolution? {
        if (
            linkedSpreadsheetId.isNotBlank() &&
            isUsableSpreadsheet(drive, linkedSpreadsheetId, requiredOwnerEmail = accountEmail)
        ) {
            return SpreadsheetResolution(linkedSpreadsheetId)
        }
        if (
            legacySpreadsheetId.isNotBlank() &&
            isUsableSpreadsheet(drive, legacySpreadsheetId, requiredOwnerEmail = accountEmail)
        ) {
            return SpreadsheetResolution(legacySpreadsheetId, adoptedLegacyId = true)
        }
        val markedFiles = listSpreadsheets(
            drive = drive,
            extraQuery = "appProperties has { key='$FINAI_SHEET_PROPERTY' and value='$FINAI_SHEET_PROPERTY_VALUE' }"
        )
        val markedSpreadsheetId = selectNewestOwnedSpreadsheetId(markedFiles, accountEmail)
        if (markedSpreadsheetId != null) return SpreadsheetResolution(markedSpreadsheetId)
        val legacyFiles = listSpreadsheets(
            drive = drive,
            extraQuery = "name contains '$LEGACY_SHEET_NAME_PREFIX'"
        )
        val legacy = selectNewestOwnedSpreadsheetId(legacyFiles, accountEmail)
        if (legacy != null) return SpreadsheetResolution(legacy)
        // Recover a workbook created by older versions before their metadata/link could be written.
        return selectNewestOwnedSpreadsheetId(listSpreadsheets(drive, "name = 'FinAI'"), accountEmail)?.let { SpreadsheetResolution(it) }
    }

    private suspend fun isUsableSpreadsheet(
        drive: Drive,
        spreadsheetId: String,
        requiredOwnerEmail: String? = null
    ): Boolean {
        return try {
            val file = runInterruptible {
                drive.files().get(spreadsheetId)
                    .setFields("id,mimeType,trashed,owners(emailAddress)")
                    .execute()
            }
            val hasRequiredOwner = requiredOwnerEmail == null || file.owners.orEmpty().any {
                it.emailAddress.equals(requiredOwnerEmail, ignoreCase = true)
            }
            file.trashed != true && file.mimeType == SPREADSHEET_MIME_TYPE && hasRequiredOwner
        } catch (error: GoogleJsonResponseException) {
            if (error.statusCode == 404) false else throw error
        }
    }

    private suspend fun listSpreadsheets(drive: Drive, extraQuery: String): List<DriveFile> = runInterruptible {
        val files = mutableListOf<DriveFile>()
        var page: String? = null
        do {
            val result = drive.files().list()
            .setSpaces("drive")
            .setQ("mimeType='$SPREADSHEET_MIME_TYPE' and trashed=false and $extraQuery")
            .setOrderBy("createdTime desc")
            .setPageSize(100)
            .setPageToken(page)
            .setFields("nextPageToken,files(id,createdTime,owners(emailAddress),appProperties)")
            .execute()
            files += result.files.orEmpty().filter { it.appProperties?.get("finaiBackupOf") == null }
            page = result.nextPageToken
        } while (page != null)
        files
    }

    private suspend fun markAsFinAiSpreadsheet(drive: Drive, spreadsheetId: String, locale: SheetsSchema.LocaleCode = SheetsSchema.LocaleCode.ES) {
        runInterruptible {
            drive.files().update(
                spreadsheetId,
                DriveFile().setAppProperties(
                    drive.files().get(spreadsheetId).setFields("appProperties").execute().appProperties.orEmpty() + mapOf(
                        FINAI_SHEET_PROPERTY to FINAI_SHEET_PROPERTY_VALUE,
                        FINAI_SCHEMA_VERSION_PROPERTY to SheetsSchema.SCHEMA_VERSION.toString(),
                        FINAI_SCHEMA_LOCALE_PROPERTY to locale.code,
                        "finaiInitializing" to "false"
                    )
                )
            )
                .setFields("id")
                .execute()
        }
    }

    private data class SpreadsheetResolution(
        val id: String,
        val adoptedLegacyId: Boolean = false
    )

    private fun resolveSchemaLocale(): SheetsSchema.LocaleCode {
        val configuration = context.resources.configuration
        val language = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            configuration.locales[0]?.language
        } else {
            @Suppress("DEPRECATION") configuration.locale.language
        }
        return SheetsSchema.localeFromCode(language)
    }

    private fun resolveSpreadsheetLocale(drive: Drive, sheets: Sheets, spreadsheetId: String): SheetsSchema.LocaleCode? {
        val file = drive.files().get(spreadsheetId).setFields("appProperties").execute()
        val metadata = sheets.spreadsheets().get(spreadsheetId).setIncludeGridData(false).execute()
        return SheetsSchema.detectLocale(file.appProperties, metadata.sheets.map { it.properties.title }, emptyList())
    }

}

internal fun selectNewestSpreadsheetId(files: List<DriveFile>): String? = files
    .asSequence()
    .filter { !it.id.isNullOrBlank() }
    .maxByOrNull { it.createdTime?.value ?: Long.MIN_VALUE }
    ?.id

internal fun selectNewestOwnedSpreadsheetId(files: List<DriveFile>, ownerEmail: String): String? =
    selectNewestSpreadsheetId(
        files.filter { file ->
            file.owners.orEmpty().any { owner ->
                owner.emailAddress.equals(ownerEmail, ignoreCase = true)
            }
        }
    )
