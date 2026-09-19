package com.gastos.feature.backup

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
class DocumentImageViewModel @Inject constructor(private val cache: DriveImageCache) : ViewModel() {
    suspend fun load(local: String?, remote: String?, account: String?, hash: String?): Bitmap = withContext(Dispatchers.IO) {
        val file = cache.resolve(local, remote, account, hash)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw ImageAccessException("INVALID_IMAGE")
    }
}

@Composable
fun DocumentImageButton(localUri: String?, fileId: String?, accountId: String?, contentHash: String?,
    viewModel: DocumentImageViewModel = hiltViewModel()) {
    if (localUri == null && fileId == null) return
    var open by remember(fileId, localUri) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.view_document_image)) }
    if (open) {
        var bitmap by remember(fileId, localUri) { mutableStateOf<Bitmap?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(fileId, localUri, retry) {
            error = null
            try { bitmap = viewModel.load(localUri, fileId, accountId, contentHash) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = (failure as? ImageAccessException)?.code ?: "IMAGE_UNAVAILABLE" }
        }
        AlertDialog(onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.view_document_image)) },
            text = { Column {
                bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.view_document_image), Modifier.fillMaxWidth().heightIn(max = 460.dp)) }
                    ?: if (error == null) CircularProgressIndicator() else Text(imageErrorMessage(error!!))
            } },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.image_close)) } },
            dismissButton = { if (error != null) TextButton(onClick = { retry++ }) { Text(stringResource(R.string.image_retry)) } })
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
