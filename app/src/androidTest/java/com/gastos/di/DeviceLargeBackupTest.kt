package com.gastos.di

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gastos.domain.model.Invoice
import com.gastos.domain.model.InvoiceType
import com.gastos.feature.backup.*
import com.gastos.repository.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/** A valid image with synthetic padding exercises aggregate size without consuming Drive storage. */
@RunWith(AndroidJUnit4::class)
class DeviceLargeBackupTest {
    @Test fun completeArchiveReads320MiBAndCancellationPreservesLiveData(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("largeBackup") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".dev"))
        assumeTrue("Requires 1 GiB free for temporary restore extraction",context.filesDir.usableSpace > 1024L*1024*1024)
        val app = EntryPointAccessors.fromApplication(context,LiveAccountTestEntryPoint::class.java)
        val before = app.snapshots().snapshot()
        val report = JSONObject()
        val source = File(context.cacheDir,"qa-large-source.png")
        val bitmap = Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        RandomAccessFile(source,"rw").use { it.setLength(40L*1024*1024) }
        val photo = app.images().persist(Uri.fromFile(source)).toString()
        val data = BackupDataset((1L..8L).map { id -> Invoice(id=id,fecha=1_790_035_200_000,proveedor="SYNTHETIC LARGE TEST $id",
            tipo=InvoiceType.GASTO,total=11.0,ivaPercent=10.0,imagenUri=photo) },emptyList(),emptyList(),emptyList(),emptyList())
        val repository = object : BackupDataRepository {
            override suspend fun snapshot() = data
            override suspend fun replaceAll(dataset: BackupDataset) = error("Must not commit to the live database")
            override suspend fun replaceAllWithRestoreMarker(dataset: BackupDataset, restoreId: String) = error("Must not commit")
            override suspend fun committedRestoreId(): String? = null
            override suspend fun clearRestoreMarker(restoreId: String) = Unit
        }
        val isolatedKeys = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("qa_large_$name",mode)
        }
        val archive = BackupArchiveService(context,repository,app.settings(),app.images(),BackupKeyStore(isolatedKeys),
            BackupRestoreJournal(context),app.outbox(),app.cache())
        archive.configurePassword("Synthetic-large-test-only".toCharArray())
        val complete = File(context.cacheDir,"qa-large-complete.finai")
        val small = File(context.cacheDir,"qa-large-data.finai")
        try {
            var started = SystemClock.elapsedRealtime()
            val dataPreview = archive.createArchive(small,BackupMode.DATA_ONLY)
            report.put("dataOnlyMs",SystemClock.elapsedRealtime()-started)
            assertEquals(0,dataPreview.imageCount)
            started = SystemClock.elapsedRealtime()
            val preview = archive.createArchive(complete,BackupMode.COMPLETE)
            report.put("completeMs",SystemClock.elapsedRealtime()-started).put("uncompressedImageBytes",8L*40*1024*1024)
            assertEquals(8,preview.imageCount)
            var reachedCommit = false
            started = SystemClock.elapsedRealtime()
            val cancelled = runCatching { complete.inputStream().use { archive.restore(it,"Synthetic-large-test-only".toCharArray()) {
                reachedCommit = true; false
            } } }
            report.put("validateAndCancelRestoreMs",SystemClock.elapsedRealtime()-started)
            assertTrue(cancelled.isFailure); assertTrue(reachedCommit)
            assertEquals(before,app.snapshots().snapshot())
            before.invoices.filter { it.imagenUri != null }.forEach { assertNotNull(app.images().managedFile(it.imagenUri)) }
            report.put("liveDataUnchanged",true).put("passed",true)
        } finally {
            source.delete(); complete.delete(); small.delete(); app.images().delete(photo)
            val output = File(context.getExternalFilesDir(null),"live-account/large-backup.json")
            output.parentFile!!.mkdirs(); output.writeText(report.toString(2))
        }
    }
}
