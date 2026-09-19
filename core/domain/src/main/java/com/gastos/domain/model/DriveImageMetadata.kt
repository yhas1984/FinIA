package com.gastos.domain.model

/** Drive-only fields; updating them must never overwrite a concurrent financial edit. */
data class DriveImageMetadata(
    val fileId: String?,
    val webViewLink: String?,
    val pending: Boolean,
    val accountId: String?,
    val contentHash: String?,
    val error: String? = null
)

enum class DocumentKind { EXPENSE, INCOME }
