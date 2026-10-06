package com.gastos.domain.model

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Currency
import java.util.Locale

@Serializable
data class BankAccount(val id: String, val name: String, val currency: String)

@Serializable
data class BankMapping(
    val date: Int = -1, val description: Int = -1, val amount: Int = -1,
    val debit: Int = -1, val credit: Int = -1, val currency: Int = -1,
    val transactionId: Int = -1, val reference: Int = -1,
    val datePattern: String = "dd/MM/yyyy", val decimalComma: Boolean = true,
    val reverseSign: Boolean = false,
    val referenceIsDocumentNumber: Boolean = false
)

@Serializable
data class BankCsvTable(val hash: String, val headers: List<String>, val rows: List<List<String>>, val headerLine: Int = 0, val delimiter: String = ";", val rowLines: List<Int> = emptyList())
data class BankPreview(val line: Int, val transaction: BankTransaction? = null, val error: String? = null)

@Serializable
enum class BankResolution { PENDING, LINKED, CREATED, TRANSFER, IGNORED, DUPLICATE, REVERSED }

@Serializable
data class BankTransaction(
    val id: String, val batchId: String, val accountId: String, val line: Int,
    val date: Long, val description: String, val amount: String, val currency: String,
    val bankId: String = "", val reference: String = "",
    val resolution: BankResolution = BankResolution.PENDING,
    val documentUuid: String? = null, val source: String? = null,
    val duplicateOf: String? = null, val bookingDate: String = "",
    val createdRevision: Long? = null,
    val schemaVersion: Int = 1
) {
    val signedAmount: BigDecimal get() = amount.toBigDecimal()
    val isExpense: Boolean get() = signedAmount.signum() < 0
}

@Serializable
data class BankBatch(val id: String, val accountId: String, val fileHash: String,
    val fileName: String, val mapping: BankMapping, val createdAt: Long,
    val rows: Int, val excludedLines: List<Int> = emptyList(), val schemaVersion: Int = 1)

@Serializable
data class BankSource(val id: String, val batchId: String, val table: BankCsvTable)

data class BankProgress(val completed: Int, val total: Int, val linked: Int = 0, val created: Int = 0)
data class BankRectification(val changed: Int, val protected: Int, val reversibleCreatedIds: List<String> = emptyList())

data class BankMovement(val uuid: String, val source: String, val expense: Boolean,
    val amount: Double, val currency: String, val date: Long, val description: String,
    val number: String? = null, val hasDocument: Boolean = false, val revision: Long = 0)

/** Deliberately conservative: equal amounts are candidates, never proof of identity. */
object BankMatching {
    fun automatic(row: BankTransaction, movements: List<BankMovement>, referenceIsDocumentNumber: Boolean): BankMovement? {
        if (!referenceIsDocumentNumber || row.reference.isBlank() || needsClassification(row.description)) return null
        val matches = candidates(row, movements).filter { movement ->
            movement.number?.let { key(it) == key(row.reference) } == true && merchantCompatible(row.description, movement.description)
        }
        return matches.singleOrNull()
    }

    private fun merchantCompatible(bankDescription: String, documentMerchant: String): Boolean {
        val bank: String = key(bankDescription)
        val merchant: String = key(documentMerchant)
        return merchant.length >= 4 && (bank == merchant || Regex("(?:^|[^a-z0-9])${Regex.escape(merchant)}(?:$|[^a-z0-9])").containsMatchIn(bank))
    }
    fun candidates(row: BankTransaction, movements: List<BankMovement>): List<BankMovement> = movements.filter {
        it.expense == row.isExpense && it.currency == row.currency && it.amount.isFinite() &&
            BigDecimal.valueOf(it.amount).compareTo(row.signedAmount.abs()) == 0 &&
            kotlin.math.abs(it.date - row.date) <= 7L * 24 * 60 * 60 * 1000
    }.sortedByDescending { it.description.let(::key) == key(row.description) }

    fun sameEntry(a: BankTransaction, b: BankTransaction): Boolean = a.accountId == b.accountId &&
        a.currency == b.currency && a.signedAmount.compareTo(b.signedAmount) == 0 &&
        (if (a.bookingDate.isNotEmpty() && b.bookingDate.isNotEmpty()) a.bookingDate == b.bookingDate else a.date == b.date) && key(a.description) == key(b.description) && a.reference == b.reference

    fun needsClassification(description: String): Boolean = Regex(
        "(?i)\\b(transfer|transferencia|traspaso|refund|devolucion|reembolso|abono|cuota|installment|liquidacion|settlement)\\b"
    ).containsMatchIn(key(description))

    fun key(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").trim().replace(Regex("\\s+"), " ")
}

/** Bounded, local CSV reader. No guessing of dates, signs or numeric grouping on malformed rows. */
object BankCsv {
    const val MAX_BYTES: Int = 5 * 1024 * 1024
    const val MAX_ROWS: Int = 10000
    val datePatterns: List<String> = listOf("dd/MM/yyyy", "MM/dd/yyyy", "yyyy-MM-dd", "dd-MM-yyyy", "dd.MM.yyyy")

    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun read(bytes: ByteArray, delimiter: Char? = null, headerLine: Int = 0): BankCsvTable {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "BANK_SIZE" }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { bytes.toString(charset("windows-1252")) }
        require(!text.contains('\u0000')) { "BANK_CSV" }
        val candidates = delimiter?.let(::listOf) ?: listOf(';', ',', '\t')
        val selected = candidates.mapNotNull { separator -> runCatching { separator to parse(text.removePrefix("\uFEFF"), separator) }.getOrNull() }
            .filter { it.second.getOrNull(headerLine)?.size?.let { width -> width >= 2 } == true }
            .maxByOrNull { (_, rows) -> rows.drop(headerLine).take(30).count { it.size == rows[headerLine].size } * rows[headerLine].size }
            ?: error("BANK_CSV")
        val parsed = selected.second
        val headers = parsed.getOrNull(headerLine) ?: error("BANK_CSV")
        val content = parsed.drop(headerLine + 1).mapIndexedNotNull { index, row -> if (row.any(String::isNotBlank)) (index + headerLine + 2) to row else null }
        require(headers.size in 2..100 && content.size in 1..MAX_ROWS) { "BANK_SIZE" }
        return BankCsvTable(hash(bytes), headers, content.map { it.second }, headerLine, selected.first.toString(), content.map { it.first })
    }

    private fun parse(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closed = false
        var index = 0
        fun cell() { row.add(field.toString().trim()); field.setLength(0); closed = false; require(row.size <= 100) { "BANK_CSV" } }
        fun line() { cell(); rows.add(row); row = mutableListOf(); require(rows.size <= MAX_ROWS + 50) { "BANK_SIZE" } }
        while (index < text.length) {
            val char = text[index++]
            if (quoted) {
                if (char == '"') {
                    if (text.getOrNull(index) == '"') { field.append('"'); index++ }
                    else { quoted = false; closed = true }
                } else field.append(char)
            } else when {
                char == delimiter -> cell()
                char == '\n' || char == '\r' -> { if (char == '\r' && text.getOrNull(index) == '\n') index++; line() }
                char == '"' && field.isEmpty() && !closed -> quoted = true
                else -> { require(!closed && char != '"') { "BANK_CSV" }; field.append(char) }
            }
            require(field.length <= 2000) { "BANK_SIZE" }
        }
        require(!quoted) { "BANK_CSV" }
        if (field.isNotEmpty() || row.isNotEmpty() || closed) line()
        return rows
    }

    fun detect(headers: List<String>): BankMapping {
        val keys = headers.map(BankMatching::key)
        fun column(vararg names: String): Int = keys.indexOfFirst { it in names }
        return BankMapping(date = column("fecha", "fecha operacion", "fecha operación", "date", "booking date", "transaction date"),
            description = column("concepto", "descripcion", "description", "merchant", "comercio", "details"),
            amount = column("importe", "amount", "signed amount"), debit = column("cargo", "debe", "debit", "withdrawal"),
            credit = column("abono", "haber", "credit", "deposit"), currency = column("moneda", "divisa", "currency"),
            reference = column("referencia", "reference", "numero factura", "invoice number"))
        // A generic "reference" is never assumed to be a unique bank transaction ID.
    }

    fun preview(table: BankCsvTable, account: BankAccount, mapping: BankMapping): List<BankPreview> {
        require(mapping.datePattern in datePatterns) { "BANK_MAPPING" }
        val required = listOf(mapping.date, mapping.description) + if (mapping.amount >= 0) listOf(mapping.amount) else listOf(mapping.debit, mapping.credit)
        val all = required + listOf(mapping.currency, mapping.transactionId, mapping.reference).filter { it >= 0 }
        require(required.all { it in table.headers.indices } && all.all { it in table.headers.indices } && listOf(mapping.currency, mapping.transactionId, mapping.reference).all { it >= -1 } && all.distinct().size == all.size) { "BANK_MAPPING" }
        val batchId = "bank:batch:${hash((account.id + ":" + table.hash).toByteArray())}"
        return table.rows.mapIndexed { index, cells ->
            val line = table.rowLines.getOrNull(index) ?: (index + table.headerLine + 2)
            try {
                require(cells.size == table.headers.size) { "BANK_COLUMNS" }
                fun value(column: Int): String = cells.getOrNull(column).orEmpty().trim()
                val date = parseDate(value(mapping.date), mapping.datePattern)
                val description = value(mapping.description)
                require(description.isNotBlank() && description.length <= 500) { "BANK_DESCRIPTION" }
                val currency = value(mapping.currency).ifBlank { account.currency }.uppercase(Locale.ROOT)
                require(currency.matches(Regex("[A-Z]{3}")) && runCatching { Currency.getInstance(currency) }.isSuccess) { "BANK_CURRENCY" }
                val amount = if (mapping.amount >= 0) number(value(mapping.amount), mapping.decimalComma)
                    .let { if (mapping.reverseSign) it.negate() else it }
                else {
                    val debit = value(mapping.debit).let { if (it.isEmpty()) BigDecimal.ZERO else number(it, mapping.decimalComma) }
                    val credit = value(mapping.credit).let { if (it.isEmpty()) BigDecimal.ZERO else number(it, mapping.decimalComma) }
                    require(debit.signum() >= 0 && credit.signum() >= 0 && (debit.signum() == 0 || credit.signum() == 0)) { "BANK_AMOUNT" }
                    credit - debit
                }
                require(amount.signum() != 0 && amount.precision() <= 15 && amount.abs() <= BigDecimal("1000000000000")) { "BANK_AMOUNT" }
                val precision = Currency.getInstance(currency).defaultFractionDigits
                require(amount.stripTrailingZeros().scale() <= precision.coerceAtLeast(0)) { "BANK_AMOUNT" }
                BankPreview(line, BankTransaction("$batchId:$line", batchId, account.id, line, date, description,
                    amount.stripTrailingZeros().toPlainString(), currency, value(mapping.transactionId), value(mapping.reference),
                    bookingDate = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(java.util.Date(date))))
            } catch (failure: IllegalArgumentException) { BankPreview(line, error = failure.message ?: "BANK_CSV") }
        }
    }

    private fun parseDate(text: String, pattern: String): Long {
        val format = SimpleDateFormat(pattern, Locale.ROOT).apply { isLenient = false }
        val position = java.text.ParsePosition(0)
        val date = format.parse(text, position)
        require(date != null && position.index == text.length && format.format(date) == text) { "BANK_DATE" }
        return date.time
    }

    fun number(text: String, decimalComma: Boolean): BigDecimal {
        val decimal = if (decimalComma) ',' else '.'
        val grouping = if (decimalComma) '.' else ','
        val normalized = text.trim().replace('\u00a0', ' ')
        val escapedGroup = Regex.escape(grouping.toString())
        val pattern = "[+-]?(?:[0-9]+|[0-9]{1,3}(?:$escapedGroup[0-9]{3})+)(?:${Regex.escape(decimal.toString())}[0-9]+)?"
        require(Regex(pattern).matches(normalized)) { "BANK_AMOUNT" }
        return normalized.replace(grouping.toString(), "").replace(decimal, '.').toBigDecimal()
    }
}
