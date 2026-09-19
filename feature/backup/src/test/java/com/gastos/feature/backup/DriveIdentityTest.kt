package com.gastos.feature.backup

import android.accounts.Account
import android.content.Context
import android.content.ContentResolver
import android.content.SharedPreferences
import android.net.Uri
import com.gastos.domain.model.*
import com.gastos.repository.*
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import com.google.api.services.drive.model.FileList
import com.google.api.services.drive.model.GeneratedIds
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpResponseException
import com.google.api.client.http.HttpHeaders
import com.google.api.client.http.AbstractInputStreamContent
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest

class DriveIdentityTest {
    private val bytes = "synthetic photograph".toByteArray()
    private val hash = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    private lateinit var service: InvoiceDriveService
    private lateinit var invoices: InvoiceRepository
    private lateinit var incomes: IncomeRepository
    private lateinit var files: Drive.Files
    private lateinit var sheets: SheetsExportService
    private var invoice = Invoice(id = 7, fecha = 1, proveedor = "Synthetic", tipo = InvoiceType.GASTO, total = 10.0, imagenUri = "local")
    private var income = Income(id = 7, fecha = 1, concepto = "Synthetic", monto = 20.0, imagenUri = "local")
    private var remote: DriveFile? = null
    private var creates = 0
    private var loseResponse = false

    @Before fun setup() {
        mockkStatic(android.text.TextUtils::class)
        every { android.text.TextUtils.isEmpty(any()) } answers { firstArg<CharSequence?>().isNullOrEmpty() }
        mockkStatic(Uri::class)
        val uri = mockk<Uri>()
        every { Uri.parse("local") } returns uri
        val resolver = mockk<ContentResolver> {
            every { openInputStream(uri) } answers { ByteArrayInputStream(bytes) }
            every { getType(uri) } returns "image/jpeg"
        }
        val prefs = mockk<SharedPreferences>(relaxed = true) {
            every { getString(any(), any()) } returns "folder"
            every { edit() } returns mockk(relaxed = true)
        }
        val context = mockk<Context> {
            every { contentResolver } returns resolver
            every { getSharedPreferences(any(), any()) } returns prefs
        }
        val account = mockk<GoogleSignInAccount> {
            every { id } returns "account-A"
            every { account } returns mockk<Account>()
        }
        sheets = mockk { every { getLastSignedInAccount() } returns account }
        invoices = mockk {
            coEvery { getInvoiceById(7) } answers { invoice }
            coEvery { updateImageSync(any(), any(), any(), any()) } answers {
                val m = arg<DriveImageMetadata>(3)
                invoice = invoice.copy(driveFileId=m.fileId, driveAccountId=m.accountId, driveContentHash=m.contentHash,
                    driveWebViewLink=m.webViewLink, driveUploadPending=m.pending, driveSyncError=m.error)
                true
            }
        }
        incomes = mockk {
            coEvery { getIncomeById(7) } answers { income }
            coEvery { updateImageSync(any(), any(), any(), any()) } answers {
                val m = arg<DriveImageMetadata>(3)
                income = income.copy(driveFileId=m.fileId, driveAccountId=m.accountId, driveContentHash=m.contentHash,
                    driveWebViewLink=m.webViewLink, driveUploadPending=m.pending, driveSyncError=m.error)
                true
            }
        }
        files = mockk(relaxed = true)
        every { files.get("folder").setFields(any()).execute() } returns DriveFile().setId("folder").setMimeType("application/vnd.google-apps.folder")
        every { files.get("reserved").setFields(any()).execute() } answers {
            remote ?: throw GoogleJsonResponseException(HttpResponseException.Builder(404,"Not found",HttpHeaders()), null)
        }
        every { files.list().setSpaces(any()).setQ(any()).setFields(any()).execute() } returns FileList().setFiles(emptyList())
        every { files.generateIds().setCount(1).setSpace("drive").execute() } returns GeneratedIds().setIds(listOf("reserved"))
        val create = mockk<Drive.Files.Create>(relaxed = true)
        every { files.create(any<DriveFile>(), any<AbstractInputStreamContent>()) } answers {
            val metadata = firstArg<DriveFile>()
            // The reference MUST have been persisted before sending any bytes.
            assertEquals("reserved", if (metadata.appProperties["finaiKind"] == "EXPENSE") invoice.driveFileId else income.driveFileId)
            remote = DriveFile().setId(metadata.id).setMd5Checksum(hash).setAppProperties(metadata.appProperties)
            creates++
            create
        }
        every { create.setFields(any()).execute() } answers {
            if (loseResponse) { loseResponse=false; throw IOException("response lost") }
            remote!!
        }
        val drive = mockk<Drive> { every { files() } returns files }
        service = spyk(InvoiceDriveService(context, sheets, invoices,
            mockk { every { isPremium } returns MutableStateFlow(true) }, mockk(relaxed=true), incomes), recordPrivateCalls = true)
        every { service["createDriveService"](any<Account>()) } returns drive
    }
    @After fun cleanup() { unmockkStatic(Uri::class); unmockkStatic(android.text.TextUtils::class) }

    @Test fun `lost response reuses durable reservation and creates just one image`() = runTest {
        val staleRequest = invoice
        loseResponse = true
        assertFalse(service.upload(staleRequest).uploaded)
        assertEquals("reserved", invoice.driveFileId)
        assertTrue(service.upload(staleRequest).uploaded)
        assertEquals(1, creates)
        assertFalse(invoice.driveUploadPending)
    }
    @Test fun `income and expense with identical local ID have different identities`() = runTest {
        assertTrue(service.upload(income).uploaded)
        assertEquals("INCOME", remote!!.appProperties["finaiKind"])
        assertEquals(income.documentUuid, remote!!.appProperties["finaiUuid"])
        assertNotEquals(invoice.documentUuid, income.documentUuid)
    }
    @Test fun `ambiguous legacy reference and wrong account are never overwritten`() = runTest {
        invoice = invoice.copy(driveFileId="reserved", driveAccountId="account-A")
        remote = DriveFile().setId("reserved").setMd5Checksum("different").setAppProperties(mapOf("finaiInvoiceId" to "7"))
        val conflict = service.upload(invoice)
        assertFalse(conflict.uploaded)
        assertEquals("IDENTITY_REVIEW_REQUIRED", conflict.message)
        assertEquals(0, creates)
        invoice = invoice.copy(driveAccountId="account-B")
        assertEquals("WRONG_ACCOUNT", service.upload(invoice).message)
        assertEquals(0, creates)
    }
    @Test fun `restored pending reservation is confirmed without the original photograph`() = runTest {
        loseResponse = true
        assertFalse(service.upload(invoice).uploaded)
        invoice = invoice.copy(imagenUri = null)
        assertTrue(service.upload(invoice).uploaded)
        assertFalse(invoice.driveUploadPending)
        assertEquals(1,creates)
    }

}
