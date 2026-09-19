package com.gastos.feature.backup

import android.content.Context
import com.gastos.storage.InvoiceImageStorage
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files

class DriveImageCacheTest {
    @Test fun `restored image downloads only on opening and cache is separated by account`() = runTest {
        val root = Files.createTempDirectory("finai-cache").toFile()
        try {
            val drive = mockk<InvoiceDriveService>()
            every { drive.activeAccountId() } returns "A"
            coEvery { drive.downloadImage("file", "A", any()) } coAnswers { arg<OutputStream>(2).write("synthetic".toByteArray()) }
            val storage = mockk<InvoiceImageStorage> { every { managedFile(any()) } returns null }
            val cache = DriveImageCache(mockk<Context> { every { cacheDir } returns root }, drive, storage)
            coVerify(exactly=0) { drive.downloadImage(any(),any(),any()) }
            val first = cache.resolve(null,"file","A",null)
            assertEquals("synthetic", first.readText())
            assertEquals(first, cache.resolve(null,"file","A",null))
            coVerify(exactly=1) { drive.downloadImage(any(),any(),any()) }
            every { drive.activeAccountId() } returns "B"
            try { cache.resolve(null,"file","A",null); fail() }
            catch (error: ImageAccessException) { assertEquals("WRONG_ACCOUNT",error.code) }
            coVerify(exactly=1) { drive.downloadImage(any(),any(),any()) }
        } finally { root.deleteRecursively() }
    }
    @Test fun `least used downloaded images are trimmed while pending originals are preserved`() = runTest {
        val root = Files.createTempDirectory("finai-cache-limit").toFile()
        try {
            val original = File(root,"pending-original").apply { RandomAccessFile(this,"rw").use { it.setLength(200L*1024*1024) } }
            val dir = File(root,"drive_images").apply { mkdirs() }
            val oldest = File(dir,"old").apply { RandomAccessFile(this,"rw").use { it.setLength(90L*1024*1024) }; setLastModified(1) }
            val drive = mockk<InvoiceDriveService> { every { activeAccountId() } returns "A" }
            coEvery { drive.downloadImage(any(),any(),any()) } coAnswers {
                val output = arg<OutputStream>(2); val block = ByteArray(1024*1024)
                repeat(15) { output.write(block) }
            }
            val cache = DriveImageCache(mockk<Context> { every { cacheDir } returns root },drive,
                mockk { every { managedFile(any()) } returns null })
            assertTrue(cache.resolve(null,"new","A",null).isFile)
            assertFalse(oldest.exists())
            assertTrue(dir.listFiles()!!.sumOf { it.length() } <= DriveImageCache.MAX_CACHE_BYTES)
            assertEquals(200L*1024*1024, original.length())
        } finally { root.deleteRecursively() }
    }
    @Test fun `missing and interrupted downloads never leave a reusable cache entry`() = runTest {
        val root=Files.createTempDirectory("finai-cache-error").toFile()
        try {
            val drive=mockk<InvoiceDriveService> { every { activeAccountId() } returns "A" }
            val cache=DriveImageCache(mockk<Context> { every { cacheDir } returns root },drive,
                mockk { every { managedFile(any()) } returns null })
            for(code in listOf("OFFLINE","PERMISSION_REQUIRED","REMOTE_FILE_MISSING")) {
                coEvery { drive.downloadImage(any(),any(),any()) } coAnswers {
                    arg<OutputStream>(2).write(1); throw ImageAccessException(code)
                }
                try { cache.resolve(null,"file","A",null); fail() }
                catch(error:ImageAccessException) { assertEquals(code,error.code) }
                assertTrue(File(root,"drive_images").listFiles()!!.isEmpty())
            }
        } finally { root.deleteRecursively() }
    }
}
