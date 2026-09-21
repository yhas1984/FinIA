package com.gastos.domain.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DocumentIdentity(
    val uuid: String,
    val localId: Long,
    val isIncome: Boolean,
    val date: String,
    val currency: String,
    val amount: Double,
    val issuer: String,
    val country: String?,
    val number: String?,
    val issuerTaxId: String?,
    val evidence: DocumentEvidence?,
    val updatedAt: Long
) {
    val version: String get() = "$uuid:$updatedAt"
    val key: String? get() = DocumentDuplicates.key(number, evidence?.document)
}

enum class DuplicateStrength { STRONG, POSSIBLE }
enum class DuplicateReason { SAME_FILE, SAME_INVOICE, SAME_PAYROLL, CONFLICT, SIMILAR_NUMBER }
data class DuplicateMatch(val existing: DocumentIdentity, val strength: DuplicateStrength, val reason: DuplicateReason)

/** Repository protection for callers that do not use the document capture coordinator. */
class DuplicateDocumentException(val matches: List<DuplicateMatch>) : IllegalStateException("DOCUMENT_ALREADY_REGISTERED")

object DocumentDuplicates {
    fun normalize(value: String?): String = value.orEmpty().trim().uppercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    fun key(number: String?, document: ScannedDocument?): String? =
        if (document?.kind == "nomina") document.payPeriod?.takeIf(String::isNotBlank)?.let { "pay:${normalize(it)}" }
        else number?.takeIf(String::isNotBlank)?.let { "inv:${normalize(it).replace('O', '0')}" }

    fun compare(incoming: DocumentIdentity, existing: DocumentIdentity): DuplicateMatch? {
        if (incoming.uuid == existing.uuid) return null
        val sourceHash: String? = incoming.evidence?.sourceSha256
        if (sourceHash != null && sourceHash == existing.evidence?.sourceSha256) {
            return DuplicateMatch(existing, DuplicateStrength.STRONG, DuplicateReason.SAME_FILE)
        }
        val a: ScannedDocument? = incoming.evidence?.document
        val b: ScannedDocument? = existing.evidence?.document
        if (a?.kind == "nomina" || b?.kind == "nomina") return comparePayroll(incoming, existing)
        val number: String = normalize(incoming.number)
        val otherNumber: String = normalize(existing.number)
        if (number.isEmpty() || otherNumber.isEmpty() || incoming.date.take(4) != existing.date.take(4)) return null
        val exactNumber: Boolean = number == otherNumber
        if (!exactNumber && number.replace('O', '0') != otherNumber.replace('O', '0')) return null
        val issuerId: String = normalize(incoming.issuerTaxId)
        val otherIssuerId: String = normalize(existing.issuerTaxId)
        if (issuerId.isNotEmpty() && otherIssuerId.isNotEmpty() && issuerId != otherIssuerId) return null
        if (!incoming.country.isNullOrBlank() && !existing.country.isNullOrBlank() && normalize(incoming.country) != normalize(existing.country)) return null
        val identified: Boolean = issuerId.isNotEmpty() && issuerId == otherIssuerId
        if (!identified && (normalize(incoming.issuer).isEmpty() || normalize(incoming.issuer) != normalize(existing.issuer))) return null
        val compatible: Boolean = incoming.date == existing.date && incoming.currency == existing.currency && incoming.amount == existing.amount
        val strong: Boolean = identified && exactNumber && compatible && incoming.isIncome == existing.isIncome
        val reason: DuplicateReason = when {
            !exactNumber -> DuplicateReason.SIMILAR_NUMBER
            !compatible || incoming.isIncome != existing.isIncome -> DuplicateReason.CONFLICT
            else -> DuplicateReason.SAME_INVOICE
        }
        return DuplicateMatch(existing, if (strong) DuplicateStrength.STRONG else DuplicateStrength.POSSIBLE, reason)
    }

    private fun comparePayroll(incoming: DocumentIdentity, existing: DocumentIdentity): DuplicateMatch? {
        val a: ScannedDocument = incoming.evidence?.document ?: return null
        val b: ScannedDocument = existing.evidence?.document ?: return null
        if (a.kind != "nomina" || b.kind != "nomina") return null
        fun samePresent(first: String?, second: String?): Boolean = normalize(first).isNotEmpty() && normalize(first) == normalize(second)
        val employer: Boolean = if (!a.issuerTaxId.isNullOrBlank() && !b.issuerTaxId.isNullOrBlank())
            samePresent(a.issuerTaxId, b.issuerTaxId) else samePresent(a.issuer, b.issuer)
        if (!employer || !samePresent(a.workerId, b.workerId) || !samePresent(a.payPeriod, b.payPeriod)) return null
        if (!a.country.isNullOrBlank() && !b.country.isNullOrBlank() && normalize(a.country) != normalize(b.country)) return null
        val sameReference: Boolean = samePresent(a.payrollReference, b.payrollReference)
        val compatible: Boolean = samePresent(a.paymentKind, b.paymentKind) && incoming.currency == existing.currency && incoming.amount == existing.amount &&
            incoming.date == existing.date && a.gross == b.gross && a.socialSecurity == b.socialSecurity && a.withholdingPercent == b.withholdingPercent
        if (!sameReference && !compatible) return null
        return DuplicateMatch(existing,
            if (sameReference && compatible && incoming.isIncome == existing.isIncome) DuplicateStrength.STRONG else DuplicateStrength.POSSIBLE,
            if (compatible) DuplicateReason.SAME_PAYROLL else DuplicateReason.CONFLICT)
    }

    fun find(incoming: DocumentIdentity, existing: List<DocumentIdentity>): List<DuplicateMatch> = existing.mapNotNull { compare(incoming, it) }
    fun blocking(incoming: DocumentIdentity, matches: List<DuplicateMatch>): List<DuplicateMatch> = matches.filter {
        it.strength == DuplicateStrength.STRONG || it.existing.version !in incoming.evidence?.distinctFrom.orEmpty()
    }
}

fun Invoice.documentIdentity(): DocumentIdentity = DocumentIdentity(documentUuid, id, tipo == InvoiceType.INGRESO,
    documentDate(fecha), moneda, total, proveedor, paisCodigo, numeroFactura, nifEmisor, evidence, updatedAt)
fun Income.documentIdentity(): DocumentIdentity = DocumentIdentity(documentUuid, id, true,
    documentDate(fecha), moneda, monto, evidence?.document?.issuer ?: fuente ?: concepto, evidence?.document?.country,
    evidence?.document?.number, evidence?.document?.issuerTaxId, evidence, updatedAt)
private fun documentDate(value: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(value))

/** The printed total is authoritative. Optional details never block capture. */
fun DocumentEvidence.toInvoice(uuid: String, imageUri: String, capturedAt: Long = System.currentTimeMillis()): Pair<Invoice, List<Product>> {
    val stored: DocumentEvidence = DocumentExtraction.prepare(this)
    val d: ScannedDocument = stored.document
    val amount: Double = DocumentExtraction.amount(d) ?: throw UnreadableDocumentAmountException()
    val invoice: Invoice = Invoice(taxes = d.taxes, documentUuid = uuid, evidence = stored, fecha = DocumentValidator.parseDate(d.date) ?: capturedAt,
        proveedor = d.issuer.orEmpty(), tipo = if (d.kind == "factura_emitida") InvoiceType.INGRESO else InvoiceType.GASTO,
        moneda = d.currency ?: "EUR", total = amount, numeroFactura = d.number, baseImponible = d.taxBase,
        cuotaIva = d.vatAmount, ivaPercent = d.vatPercent, irpfPercent = d.withholdingPercent ?: 0.0,
        paisCodigo = d.country.orEmpty(), nifEmisor = d.issuerTaxId, nifReceptor = d.recipientTaxId, categoria = d.category,
        subcategoria = d.subcategory, imagenUri = imageUri, driveUploadPending = true, ocrRawText = originalExtraction)
    val products: List<Product> = d.lines.mapNotNull { line ->
        // Partial rows remain in evidence; do not manufacture quantities or prices for the product table.
        val description: String = line.description?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val quantity: Double = line.quantity?.takeIf(Double::isFinite) ?: return@mapNotNull null
        val price: Double = line.unitPrice?.takeIf(Double::isFinite) ?: return@mapNotNull null
        val subtotal: Double = line.subtotal?.takeIf(Double::isFinite) ?: return@mapNotNull null
        val vat: Double? = line.vatPercent
        Product(taxes = line.taxes, pricesIncludeTax = d.priceBasis != "tax_excluded", invoiceId = 0, descripcion = description, cantidad = quantity,
            precioUnitario = price, subtotal = subtotal, ivaPercent = vat,
            ivaAmount = DocumentTaxes.lineTax(line, d.priceBasis))
    }
    return invoice to products
}

fun DocumentEvidence.toPayroll(uuid: String, imageUri: String, capturedAt: Long = System.currentTimeMillis()): Income {
    val stored: DocumentEvidence = DocumentExtraction.prepare(this)
    val d: ScannedDocument = stored.document
    val amount: Double = DocumentExtraction.amount(d) ?: throw UnreadableDocumentAmountException()
    return Income(taxes = d.taxes, documentUuid = uuid, evidence = stored, fecha = DocumentValidator.parseDate(d.date) ?: capturedAt,
        concepto = d.issuer.orEmpty(), fuente = d.issuer, monto = amount, totalDevengado = d.gross ?: 0.0,
        totalNeto = amount, moneda = d.currency ?: "EUR", categoria = d.category, subcategoria = d.subcategory,
        irpfPercent = d.withholdingPercent ?: 0.0, imagenUri = imageUri, driveUploadPending = true)
}
