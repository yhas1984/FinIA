package com.gastos.feature.ai

/** A failed availability check must never be presented as an invalid credential. */
sealed interface GeminiKeyValidation {
    data object Valid : GeminiKeyValidation
    data class Rejected(val message: String) : GeminiKeyValidation
    data class Unavailable(val reason: Reason, val message: String) : GeminiKeyValidation
    enum class Reason { QUOTA, CONNECTION, MODEL, REQUEST, SAFETY, RESPONSE }
}

internal fun keyValidationFailure(error: Exception, message: String): GeminiKeyValidation {
    if (error is GeminiApiException && error.category == GeminiFailure.AUTH) return GeminiKeyValidation.Rejected(message)
    val reason = when ((error as? GeminiApiException)?.category) {
        GeminiFailure.DAILY_QUOTA, GeminiFailure.GLOBAL_QUOTA -> GeminiKeyValidation.Reason.QUOTA
        GeminiFailure.MODEL_UNAVAILABLE -> GeminiKeyValidation.Reason.MODEL
        GeminiFailure.BAD_REQUEST -> GeminiKeyValidation.Reason.REQUEST
        GeminiFailure.SAFETY -> GeminiKeyValidation.Reason.SAFETY
        GeminiFailure.INVALID_OUTPUT -> GeminiKeyValidation.Reason.RESPONSE
        else -> if ((error as? GeminiApiException)?.statusCode == 429) GeminiKeyValidation.Reason.QUOTA else GeminiKeyValidation.Reason.CONNECTION
    }
    return GeminiKeyValidation.Unavailable(reason, message)
}
