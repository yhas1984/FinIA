package com.gastos.feature.backup

import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAuthIOException
import com.google.api.client.googleapis.extensions.android.gms.auth.GooglePlayServicesAvailabilityIOException
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import java.io.IOException
import kotlinx.coroutines.CancellationException

internal enum class GoogleApiErrorCategory {
    CANCELLATION,
    AUTH_RECOVERABLE,
    PLAY_SERVICES,
    AUTH_PERMANENT,
    QUOTA_OR_PERMISSION,
    TRANSIENT,
    NETWORK,
    LOCAL
}

internal data class GoogleApiErrorResult(
    val category: GoogleApiErrorCategory,
    val message: String,
    val shouldRetry: Boolean
)

internal object GoogleApiErrorClassifier {
    fun classify(error: Throwable, context: GoogleApiErrorContext): GoogleApiErrorResult {
        return when (error) {
            is CancellationException -> GoogleApiErrorResult(GoogleApiErrorCategory.CANCELLATION, "", false)
            is GooglePlayServicesAvailabilityIOException -> GoogleApiErrorResult(
                GoogleApiErrorCategory.PLAY_SERVICES,
                "Actualiza Google Play Services o vuelve a conectar Google para reanudar ${context.featureLabel}.",
                false
            )
            is UserRecoverableAuthIOException -> GoogleApiErrorResult(
                GoogleApiErrorCategory.AUTH_RECOVERABLE,
                "Vuelve a conectar Google para reanudar ${context.featureLabel}.",
                false
            )
            is GoogleAuthIOException -> GoogleApiErrorResult(
                GoogleApiErrorCategory.AUTH_PERMANENT,
                "Vuelve a conectar Google para reanudar ${context.featureLabel}.",
                false
            )
            is GoogleJsonResponseException -> classifyHttp(error, context)
            is IOException -> GoogleApiErrorResult(
                GoogleApiErrorCategory.NETWORK,
                context.networkMessage,
                true
            )
            else -> GoogleApiErrorResult(
                GoogleApiErrorCategory.LOCAL,
                context.genericMessage,
                false
            )
        }
    }

    private fun classifyHttp(error: GoogleJsonResponseException, context: GoogleApiErrorContext): GoogleApiErrorResult {
        val reason = error.details?.errors?.firstOrNull()?.reason.orEmpty()
        return when {
            error.statusCode == 401 -> GoogleApiErrorResult(
                GoogleApiErrorCategory.AUTH_PERMANENT,
                "Vuelve a conectar Google para reanudar ${context.featureLabel}.",
                false
            )
            error.statusCode == 403 && reason in TRANSIENT_403_REASONS -> GoogleApiErrorResult(
                GoogleApiErrorCategory.TRANSIENT,
                context.transientMessage,
                true
            )
            error.statusCode == 403 -> GoogleApiErrorResult(
                GoogleApiErrorCategory.QUOTA_OR_PERMISSION,
                context.quotaMessage,
                false
            )
            error.statusCode == 429 || error.statusCode in 500..599 -> GoogleApiErrorResult(
                GoogleApiErrorCategory.TRANSIENT,
                context.transientMessage,
                true
            )
            else -> GoogleApiErrorResult(
                GoogleApiErrorCategory.LOCAL,
                context.genericMessage,
                false
            )
        }
    }

    private val TRANSIENT_403_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded")
}

internal data class GoogleApiErrorContext(
    val featureLabel: String,
    val networkMessage: String,
    val transientMessage: String,
    val quotaMessage: String,
    val genericMessage: String
)
