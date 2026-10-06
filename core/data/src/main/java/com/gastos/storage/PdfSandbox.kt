package com.gastos.storage

import android.app.Service
import android.content.*
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.*
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** PDF bytes are rendered in a process without the application's credentials or network permissions. */
class PdfSandboxService : Service() {
    override fun onBind(intent: Intent): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in 1..2) return super.onTransact(code, data, reply, flags)
            try {
                val source: ParcelFileDescriptor = requireNotNull(data.readParcelable(ParcelFileDescriptor::class.java.classLoader))
                PdfRenderer(source).use { renderer ->
                    if (code == 1) { reply?.writeNoException(); reply?.writeInt(renderer.pageCount) }
                    else {
                        val index: Int = data.readInt()
                        val destination: ParcelFileDescriptor = requireNotNull(data.readParcelable(ParcelFileDescriptor::class.java.classLoader))
                        destination.use { output ->
                            renderer.openPage(index).use { page ->
                                require(page.width > 0 && page.height > 0)
                                val scale: Double = 1600.0 / maxOf(page.width, page.height)
                                val bitmap: Bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                try {
                                    bitmap.eraseColor(Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    ParcelFileDescriptor.AutoCloseOutputStream(output).use {
                                        // Lossless PNG avoids the device JPEG encoder in this isolated
                                        // process and keeps text sharp. Its quality argument is ignored.
                                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PDF_RENDER_FAILED" }
                                    }
                                } finally { bitmap.recycle() }
                            }
                        }
                        reply?.writeNoException()
                    }
                }
            } catch (error: Exception) { reply?.writeException(IllegalArgumentException("PDF_INVALID_OR_PROTECTED")) }
            return true
        }
    }
}

object PdfSandbox {
    const val MAX_BYTES: Long = 10L * 1024 * 1024
    const val MAX_PAGES: Int = 20
    suspend fun pageCount(context: Context, file: File): Int = request(context, file, null, 0)
    /** Writes a PNG preview; the original PDF is never rewritten. */
    suspend fun render(context: Context, file: File, page: Int, output: File) { request(context, file, output, page) }
    private suspend fun request(context: Context, file: File, output: File?, page: Int): Int = withContext(Dispatchers.IO) {
        withPdfRequestTimeout {
            var isBound: Boolean = false
            lateinit var connection: ServiceConnection
            try {
                val binder: IBinder = suspendCancellableCoroutine { continuation ->
                    connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, service: IBinder) { if (continuation.isActive) continuation.resume(service) }
                        override fun onServiceDisconnected(name: ComponentName) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("PDF_RENDERER_UNAVAILABLE")) }
                        override fun onNullBinding(name: ComponentName) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("PDF_RENDERER_UNAVAILABLE")) }
                        override fun onBindingDied(name: ComponentName) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("PDF_RENDERER_UNAVAILABLE")) }
                    }
                    isBound = context.bindService(Intent(context, PdfSandboxService::class.java), connection, Context.BIND_AUTO_CREATE)
                    if (!isBound) continuation.resumeWithException(IllegalStateException("PDF_RENDERER_UNAVAILABLE"))
                }
                runInterruptible {
                    val data: Parcel = Parcel.obtain()
                    val reply: Parcel = Parcel.obtain()
                    try {
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
                            data.writeParcelable(source, 0)
                            if (output == null) check(binder.transact(1, data, reply, 0))
                            else ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY).use { destination ->
                                data.writeInt(page); data.writeParcelable(destination, 0)
                                check(binder.transact(2, data, reply, 0))
                            }
                            reply.readException()
                            if (output == null) reply.readInt() else 0
                        }
                    } finally { data.recycle(); reply.recycle() }
                }
            } finally { if (isBound) context.unbindService(connection) }
        }
    }
}
