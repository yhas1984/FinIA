package com.gastos.feature.backup

/** Local numeric IDs and a UUID alone cannot authorize deletion from the other movement table. */
internal data class SheetsDocumentKey(val target: RemoteSyncTarget, val uuid: String) {
    init {
        require(target == RemoteSyncTarget.EXPENSE_SHEETS || target == RemoteSyncTarget.INCOME_SHEETS)
        require(uuid.isNotBlank())
    }
    val kind: String get() = if (target == RemoteSyncTarget.EXPENSE_SHEETS) "EXPENSE" else "INCOME"
    val fingerprintKey: String get() = "$kind:$uuid"
}
