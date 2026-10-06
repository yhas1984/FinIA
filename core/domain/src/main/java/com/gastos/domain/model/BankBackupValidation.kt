package com.gastos.domain.model

object BankBackupValidation {
    fun validate(records: List<AutomationRecord>) {
        val accounts = records.filter { it.type == "BANK_ACCOUNT" }.map { record ->
            AutomationCodec.json.decodeFromString<BankAccount>(record.payload).also {
                require(record.id == it.id && it.id.startsWith("bank:account:") && it.name.length in 1..80)
                require(runCatching { java.util.Currency.getInstance(it.currency) }.isSuccess)
            }
        }.associateBy { it.id }
        val batches = records.filter { it.type == "BANK_BATCH" }.map { record ->
            AutomationCodec.json.decodeFromString<BankBatch>(record.payload).also {
                require(it.schemaVersion == 1)
                require(record.id == it.id && it.id.startsWith("bank:batch:") && accounts.containsKey(it.accountId))
                require(it.fileHash.matches(Regex("[0-9a-f]{64}")) && it.rows in 1..BankCsv.MAX_ROWS)
            }
        }.associateBy { it.id }
        val rows = records.filter { it.type == "BANK_ROW" }.map { record ->
            AutomationCodec.json.decodeFromString<BankTransaction>(record.payload).also {
                require(record.id == it.id && it.id == "${it.batchId}:${it.line}" && batches[it.batchId]?.accountId == it.accountId)
                require(it.line >= 2 && it.description.length in 1..500 && it.bankId.length <= 2000 && it.reference.length <= 2000)
                require(it.signedAmount.signum() != 0 && it.signedAmount.abs() <= java.math.BigDecimal("1000000000000"))
                require(it.signedAmount.precision() <= 15 && runCatching { java.util.Currency.getInstance(it.currency) }.isSuccess)
                val linked = it.resolution in setOf(BankResolution.CREATED, BankResolution.LINKED)
                require(!linked || (!it.documentUuid.isNullOrBlank() && it.source in setOf("INVOICE", "INCOME")))
                require(linked || (it.documentUuid == null && it.source == null))
            }
        }
        require(rows.mapNotNull { it.documentUuid?.let { uuid -> it.source to uuid } }.distinct().size == rows.count { it.documentUuid != null })
        batches.values.forEach { batch -> require(rows.count { it.batchId == batch.id } == batch.rows) }
        val byId = rows.associateBy { it.id }
        rows.filter { it.resolution == BankResolution.DUPLICATE }.forEach {
            require(it.id != it.duplicateOf && byId[it.duplicateOf]?.let { other -> BankMatching.sameEntry(it, other) } == true)
        }
        records.filter { it.type == "BANK_SOURCE" }.forEach { record ->
            val source: BankSource = AutomationCodec.json.decodeFromString(record.payload)
            require(record.id == source.id && source.id == "bank:source:${source.batchId}")
            require(batches[source.batchId]?.fileHash == source.table.hash && source.table.headers.size in 2..100 && source.table.rows.size in 1..BankCsv.MAX_ROWS)
            require(source.table.rowLines.isEmpty() || (source.table.rowLines.size == source.table.rows.size && source.table.rowLines.zipWithNext().all { (a, b) -> b > a } && source.table.rowLines.all { it >= source.table.headerLine + 2 }))
            require(source.table.rows.all { it.size <= 100 && it.all { field -> field.length <= 2000 } })
        }
    }
}
