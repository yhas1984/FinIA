package com.gastos.feature.backup

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class DocumentImageViewModel @Inject constructor(private val cache: DriveImageCache, @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context) : ViewModel() {
    suspend fun pages(local: String?, remote: String?, account: String?, hash: String?): Int = withContext(Dispatchers.IO) {
        val file = cache.resolve(local, remote, account, hash)
        val pdf = file.inputStream().use { ByteArray(5).let { bytes -> it.read(bytes); String(bytes, Charsets.US_ASCII) } == "%PDF-" }
        if (pdf) com.gastos.storage.PdfSandbox.pageCount(context, file) else 1
    }
    suspend fun load(local: String?, remote: String?, account: String?, hash: String?, page: Int = 0): Bitmap = withContext(Dispatchers.IO) {
        val file = cache.resolve(local, remote, account, hash)
        val pdf = file.inputStream().use { ByteArray(5).let { bytes -> it.read(bytes); String(bytes, Charsets.US_ASCII) } == "%PDF-" }
        val preview: java.io.File? = if (pdf) java.io.File.createTempFile("pdf_preview_", ".png", context.cacheDir) else null
        try {
            if (preview != null) com.gastos.storage.PdfSandbox.render(context, file, page, preview)
            val source = preview ?: file
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
            BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw ImageAccessException("INVALID_IMAGE")
        } finally { preview?.delete() }
    }
}

internal class DocumentZoomState(scale: Float = 1f, panX: Float = 0f, panY: Float = 0f) {
    var scale by mutableFloatStateOf(scale)
    var panX by mutableFloatStateOf(panX)
    var panY by mutableFloatStateOf(panY)
}

private val DocumentZoomSaver = mapSaver<MutableMap<Int, DocumentZoomState>>(
    save = { pages -> pages.entries.associate { (page, state) -> page.toString() to listOf(state.scale, state.panX, state.panY) } },
    restore = { saved -> saved.entries.associate { (page, raw) ->
        val values = raw as List<*>
        page.toInt() to DocumentZoomState((values[0] as Number).toFloat(), (values[1] as Number).toFloat(), (values[2] as Number).toFloat())
    }.toMutableMap() }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentImageButton(localUri: String?, fileId: String?, accountId: String?, contentHash: String?,
    viewModel: DocumentImageViewModel = hiltViewModel()) {
    if (localUri == null && fileId == null) return
    var open by rememberSaveable(fileId, localUri) { mutableStateOf(false) }
    var page by rememberSaveable(fileId, localUri) { mutableIntStateOf(0) }
    // Register before any asynchronous image is available, so recreation cannot consume
    // or discard the zoom state while the viewer temporarily displays its loading state.
    val pageZoom = rememberSaveable(fileId, localUri, saver = DocumentZoomSaver) { mutableMapOf<Int, DocumentZoomState>() }
    val zoom = pageZoom.getOrPut(page) { DocumentZoomState() }
    var retry by remember { mutableIntStateOf(0) }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.view_document_source)) }
    if (open) {
        var bitmap by remember(fileId, localUri) { mutableStateOf<Bitmap?>(null) }
        var renderedPage by remember(fileId, localUri) { mutableStateOf<Int?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var failedPage by remember { mutableStateOf<Int?>(null) }
        var pageCount by remember(fileId, localUri) { mutableIntStateOf(0) }
        LaunchedEffect(fileId, localUri, accountId, contentHash, retry) {
            error = null
            failedPage = null
            try { pageCount = viewModel.pages(localUri, fileId, accountId, contentHash); page = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = (failure as? ImageAccessException)?.code ?: "IMAGE_UNAVAILABLE" }
        }
        LaunchedEffect(fileId, localUri, accountId, contentHash, retry, pageCount) {
            bitmap = null
            renderedPage = null
            if (pageCount <= 0) return@LaunchedEffect
            loadDocumentPages(
                requests = snapshotFlow { page },
                currentPage = { page },
                render = { requested -> viewModel.load(localUri, fileId, accountId, contentHash, requested) },
                loading = { bitmap = null; renderedPage = null; error = null; failedPage = null },
                ready = { requested, image -> bitmap = image; renderedPage = requested },
                failed = { requested, failure ->
                    failedPage = requested
                    error = (failure as? ImageAccessException)?.code ?: "IMAGE_UNAVAILABLE"
                },
                discard = { image -> image.recycle() }
            )
        }
        Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
                TopAppBar(title = { Text(stringResource(R.string.view_document_source)) }, navigationIcon = {
                    IconButton(onClick = { open = false }) { Icon(Icons.Default.Close, stringResource(R.string.image_close)) }
                })
            }, bottomBar = {
                if (pageCount > 1) Surface(tonalElevation = 2.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton(onClick = { page-- }, enabled = page > 0) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, stringResource(R.string.document_previous_page)) }
                        Text(stringResource(R.string.document_page_count, page + 1, pageCount), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        IconButton(onClick = { page++ }, enabled = page + 1 < pageCount) { Icon(Icons.AutoMirrored.Filled.NavigateNext, stringResource(R.string.document_next_page)) }
                    }
                }
            }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    val visibleError = error.takeIf { failedPage == null || failedPage == page }
                    bitmap?.takeIf { renderedPage == page }?.let { ZoomableDocument(it, page, zoom) }
                        ?: if (visibleError == null) CircularProgressIndicator() else Column(horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                            Text(imageErrorMessage(visibleError))
                            TextButton(onClick = { pageCount = 0; retry++ }) { Text(stringResource(R.string.image_retry)) }
                        }
                }
            }
        }
    }
}

@Composable
internal fun ZoomableDocument(bitmap: Bitmap, page: Int, zoom: DocumentZoomState) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    fun clampPan() {
        if (viewport.width == 0 || viewport.height == 0) return
        val fit = minOf(viewport.width.toFloat() / bitmap.width, viewport.height.toFloat() / bitmap.height)
        val maxX = ((bitmap.width * fit * zoom.scale - viewport.width) / 2).coerceAtLeast(0f)
        val maxY = ((bitmap.height * fit * zoom.scale - viewport.height) / 2).coerceAtLeast(0f)
        zoom.panX = zoom.panX.takeIf { it.isFinite() }?.coerceIn(-maxX, maxX) ?: 0f
        zoom.panY = zoom.panY.takeIf { it.isFinite() }?.coerceIn(-maxY, maxY) ?: 0f
    }
    fun setZoom(value: Float) {
        zoom.scale = value.takeIf { it.isFinite() }?.coerceIn(1f, 6f) ?: 1f
        clampPan()
    }
    val transform = rememberTransformableState { zoomFactor, pan, _ ->
        setZoom(zoom.scale * zoomFactor)
        zoom.panX += pan.x
        zoom.panY += pan.y
        clampPan()
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { viewport = it; clampPan() }
            .transformable(transform).pointerInput(page) { detectTapGestures(onDoubleTap = { setZoom(if (zoom.scale > 1f) 1f else 2.5f) }) }) {
            Image(bitmap.asImageBitmap(), stringResource(R.string.document_page, page + 1), contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.scale; scaleY = zoom.scale; translationX = zoom.panX; translationY = zoom.panY })
        }
        Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { setZoom(zoom.scale / 1.5f) }, enabled = zoom.scale > 1f) { Icon(Icons.Default.ZoomOut, stringResource(R.string.document_zoom_out)) }
            TextButton(onClick = { setZoom(1f) }) { Text(stringResource(R.string.document_zoom_reset, (zoom.scale * 100).toInt())) }
            IconButton(onClick = { setZoom(zoom.scale * 1.5f) }, enabled = zoom.scale < 6f) { Icon(Icons.Default.ZoomIn, stringResource(R.string.document_zoom_in)) }
        }
    }
}

@Composable
internal fun imageErrorMessage(code: String): String = stringResource(when (code) {
    "OFFLINE", "NETWORK" -> R.string.image_offline
    "WRONG_ACCOUNT" -> R.string.image_wrong_account
    "AUTH_REQUIRED", "AUTH_OR_LINK_REQUIRED", "PERMISSION_REQUIRED", "PERMISSION_OR_QUOTA" -> R.string.image_permissions
    "REMOTE_FILE_MISSING" -> R.string.image_deleted
    "MISSING_SOURCE" -> R.string.image_missing_source
    "IDENTITY_REVIEW_REQUIRED" -> R.string.image_identity_review
    "PREMIUM_REQUIRED" -> R.string.drive_upload_requires_premium
    else -> R.string.image_unavailable
})
