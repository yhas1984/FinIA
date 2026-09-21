package com.gastos.feature.backup

import android.content.Context

internal fun sheetsFailureCode(error: Exception): String {
    val code = error.message?.substringBefore(':')
    if (code?.startsWith("SHEETS_") == true || code in setOf("AUTH_REQUIRED", "WRONG_ACCOUNT", "PREMIUM_REQUIRED", "AUTH_OR_LINK_REQUIRED")) return code!!
    return GoogleApiErrorClassifier.classify(error,
        GoogleApiErrorContext("sheets", "OFFLINE", "SERVER_UNAVAILABLE", "PERMISSION_OR_QUOTA", "SYNC_FAILED")).category.name
}

internal fun sheetsErrorMessage(context: Context, code: String?): String? = code?.let {
    context.getString(when {
        it.startsWith("SHEETS_OTHER_DEVICE") -> R.string.sheets_error_other_device
        it.startsWith("SHEETS_NEWER_SCHEMA") || it.startsWith("SHEETS_UNKNOWN_SCHEMA") -> R.string.sheets_error_version
        it.startsWith("SHEETS_LEGACY_") || it.startsWith("SHEETS_AMBIGUOUS") || it.startsWith("SHEETS_DUPLICATE") -> R.string.sheets_error_identity
        it.startsWith("SHEETS_BACKUP") -> R.string.sheets_error_safety_copy
        it == "SHEETS_CREATION_PENDING" -> R.string.sheets_error_creation_pending
        it == "SHEETS_LINK_UNAVAILABLE" -> R.string.sheets_error_link_unavailable
        it == "SHEETS_LINK_TRASHED" -> R.string.sheets_error_link_trashed
        it in setOf("AUTH_OR_LINK_REQUIRED","WRONG_ACCOUNT","AUTH_RECOVERABLE","AUTH_PERMANENT","AUTH_REQUIRED") -> R.string.sheets_error_account
        it in setOf("NETWORK","OFFLINE","TRANSIENT","REMOTE_UNAVAILABLE") -> R.string.sheets_error_network
        else -> R.string.sheets_error_generic
    })
}
