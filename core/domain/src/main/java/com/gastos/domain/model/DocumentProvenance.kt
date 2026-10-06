package com.gastos.domain.model

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DocumentProvenance {
    fun markManual(previous: DocumentEvidence?, document: ScannedDocument): DocumentEvidence {
        val old = previous?.document?.let { DocumentEvidenceCodec.json.encodeToJsonElement(ScannedDocument.serializer(), it).jsonObject }.orEmpty()
        val current = DocumentEvidenceCodec.json.encodeToJsonElement(ScannedDocument.serializer(), document).jsonObject
        val changed = current.filter { (name, value) -> old[name] != value }.keys
        val evidence = previous ?: DocumentEvidence(document)
        return evidence.copy(document = document, correctedFields = evidence.correctedFields + changed,
            fieldOrigins = evidence.fieldOrigins + changed.associateWith { DocumentFieldOrigin.MANUAL })
    }

    fun prepareCapture(evidence: DocumentEvidence, currency: String, capturedAt: Long): DocumentEvidence {
        val prepared = DocumentExtraction.prepare(evidence)
        val document = prepared.document
        val origins = prepared.fieldOrigins.toMutableMap()
        DocumentEvidenceCodec.json.encodeToJsonElement(ScannedDocument.serializer(), document).jsonObject
            .filterValues { it != JsonNull }.keys.forEach { key -> origins.putIfAbsent(key, DocumentFieldOrigin.EXTRACTED) }
        prepared.derivedFields.forEach { origins[it] = prepared.fieldOrigins[it] ?: DocumentFieldOrigin.DERIVED }
        val date = if (DocumentValidator.parseDate(document.date) == null) {
            origins["date"] = DocumentFieldOrigin.CAPTURE
            SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(capturedAt))
        } else document.date
        val selectedCurrency = document.currency?.takeIf { it.matches(Regex("[A-Z]{3}")) } ?: currency.also {
            origins["currency"] = DocumentFieldOrigin.PREFERENCE
        }
        return prepared.copy(document = document.copy(date = date, currency = selectedCurrency), fieldOrigins = origins)
    }
}

data class InvoiceEnrichment(val value: Invoice, val conflicts: List<String>)
data class IncomeEnrichment(val value: Income, val conflicts: List<String>)

/** Enriches existing evidence; absent extraction never erases a known or manually corrected value. */
object DocumentEnrichment {
    private fun protected(previous: DocumentEvidence?, field: String, revision: Long): Boolean =
        previous?.fieldOrigins?.get(field) == DocumentFieldOrigin.MANUAL || field in previous?.correctedFields.orEmpty() ||
            (revision > 0 && previous?.fieldOrigins.isNullOrEmpty())

    fun invoice(previous: Invoice, scanned: Invoice, keepExisting: Boolean = true): InvoiceEnrichment {
        val conflicts = mutableListOf<String>()
        fun <T> select(field: String, old: T?, new: T?): T? {
            if (new == null || (old != null && scanned.evidence?.fieldOrigins?.get(field) in setOf(DocumentFieldOrigin.CAPTURE, DocumentFieldOrigin.PREFERENCE))) return old
            if (old != null && old != new && protected(previous.evidence, field, previous.financialRevision)) {
                conflicts.add(field)
                if (keepExisting) return old
            }
            return new
        }
        val date = select("date", previous.fecha, scanned.fecha)!!
        val currency = select("currency", previous.moneda, scanned.moneda)!!
        val merchant = select("issuer", previous.proveedor.takeIf(String::isNotBlank), scanned.proveedor.takeIf(String::isNotBlank)).orEmpty()
        val taxes = select("taxes", previous.taxes.takeIf { it.isNotEmpty() }, scanned.taxes.takeIf { it.isNotEmpty() }).orEmpty()
        val value = scanned.copy(id = previous.id, documentUuid = previous.documentUuid, fecha = date, moneda = currency, proveedor = merchant,
            numeroFactura = select("number", previous.numeroFactura, scanned.numeroFactura),
            baseImponible = select("taxBase", previous.baseImponible, scanned.baseImponible),
            cuotaIva = select("vatAmount", previous.cuotaIva, scanned.cuotaIva),
            ivaPercent = select("vatPercent", previous.ivaPercent, scanned.ivaPercent),
            nifEmisor = select("issuerTaxId", previous.nifEmisor, scanned.nifEmisor),
            nifReceptor = select("recipientTaxId", previous.nifReceptor, scanned.nifReceptor),
            irpfPercent = select("withholdingPercent", previous.irpfPercent.takeIf { previous.evidence?.document?.withholdingPercent != null || it != 0.0 }, scanned.irpfPercent.takeIf { scanned.evidence?.document?.withholdingPercent != null || it != 0.0 }) ?: previous.irpfPercent,
            paisCodigo = select("country", previous.paisCodigo.takeIf(String::isNotBlank), scanned.paisCodigo.takeIf(String::isNotBlank)).orEmpty(),
            taxes = taxes, categoria = previous.categoria ?: scanned.categoria, subcategoria = previous.subcategoria ?: scanned.subcategoria,
            categoryId = previous.categoryId ?: scanned.categoryId, subcategoryId = previous.subcategoryId ?: scanned.subcategoryId,
            notas = previous.notas, createdAt = previous.createdAt, financialRevision = previous.financialRevision + 1,
            manualAmountAdjusted = previous.manualAmountAdjusted)
        val old = previous.evidence?.document
        val document = value.evidence?.document?.copy(date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(date)), issuer = merchant, currency = currency,
            number = value.numeroFactura, taxBase = value.baseImponible, vatAmount = value.cuotaIva, vatPercent = value.ivaPercent,
            issuerTaxId = value.nifEmisor, recipientTaxId = value.nifReceptor,
            lines = select("lines", old?.lines?.takeIf { it.isNotEmpty() }, value.evidence?.document?.lines?.takeIf { it.isNotEmpty() }).orEmpty(),
            linesComplete = select("linesComplete", old?.linesComplete, value.evidence?.document?.linesComplete), taxes = taxes, taxesComplete = select("taxesComplete", old?.taxesComplete, value.evidence?.document?.taxesComplete), country = value.paisCodigo,
            withholdingAmount = select("withholdingAmount", old?.withholdingAmount, value.evidence?.document?.withholdingAmount),
            discount = select("discount", old?.discount, value.evidence?.document?.discount), priceBasis = select("priceBasis", old?.priceBasis, value.evidence?.document?.priceBasis),
            withholdingPercent = value.irpfPercent.takeIf { value.evidence?.document?.withholdingPercent != null || it != 0.0 }, category = value.categoria, subcategory = value.subcategoria)
        return InvoiceEnrichment(value.copy(evidence = mergeEvidence(previous.evidence, value.evidence, document, conflicts, keepExisting)), conflicts.distinct())
    }

    fun income(previous: Income, scanned: Income, keepExisting: Boolean = true): IncomeEnrichment {
        val conflicts = mutableListOf<String>()
        fun <T> select(field: String, old: T?, new: T?): T? {
            if (new == null || (old != null && scanned.evidence?.fieldOrigins?.get(field) in setOf(DocumentFieldOrigin.CAPTURE, DocumentFieldOrigin.PREFERENCE))) return old
            if (old != null && old != new && protected(previous.evidence, field, previous.financialRevision)) {
                conflicts.add(field)
                if (keepExisting) return old
            }
            return new
        }
        val date = select("date", previous.fecha, scanned.fecha)!!
        val currency = select("currency", previous.moneda, scanned.moneda)!!
        val concept = select("concept", previous.concepto.takeIf(String::isNotBlank), scanned.concepto.takeIf(String::isNotBlank)).orEmpty()
        val old = previous.evidence?.document
        val fresh = scanned.evidence?.document
        val gross = select("gross", old?.gross ?: previous.totalDevengado.takeIf { it != 0.0 }, fresh?.gross ?: scanned.totalDevengado.takeIf { it != 0.0 })
        val net = select("net", old?.net ?: previous.totalNeto.takeIf { it != 0.0 }, fresh?.net ?: scanned.totalNeto.takeIf { it != 0.0 })
        val taxes = select("taxes", previous.taxes.takeIf { it.isNotEmpty() }, scanned.taxes.takeIf { it.isNotEmpty() }).orEmpty()
        val value = scanned.copy(id = previous.id, documentUuid = previous.documentUuid, fecha = date, moneda = currency, concepto = concept,
            fuente = select("issuer", previous.fuente, scanned.fuente), totalDevengado = gross ?: 0.0, totalNeto = net ?: 0.0,
            ivaPercent = select("vatPercent", previous.ivaPercent, scanned.ivaPercent), taxes = taxes,
            irpfPercent = select("withholdingPercent", previous.irpfPercent.takeIf { old?.withholdingPercent != null || it != 0.0 }, scanned.irpfPercent.takeIf { fresh?.withholdingPercent != null || it != 0.0 }) ?: previous.irpfPercent,
            categoria = previous.categoria ?: scanned.categoria, subcategoria = previous.subcategoria ?: scanned.subcategoria,
            categoryId = previous.categoryId ?: scanned.categoryId, subcategoryId = previous.subcategoryId ?: scanned.subcategoryId,
            notas = previous.notas, createdAt = previous.createdAt, financialRevision = previous.financialRevision + 1,
            manualAmountAdjusted = previous.manualAmountAdjusted)
        val payroll = fresh?.payroll?.let { incoming -> incoming.copy(
            paymentDate = incoming.paymentDate ?: old?.payroll?.paymentDate,
            paymentDateText = incoming.paymentDateText ?: old?.payroll?.paymentDateText,
            issueDate = incoming.issueDate ?: old?.payroll?.issueDate,
            issueDateText = incoming.issueDateText ?: old?.payroll?.issueDateText,
            periodStart = incoming.periodStart ?: old?.payroll?.periodStart,
            periodEnd = incoming.periodEnd ?: old?.payroll?.periodEnd,
            dateBasis = incoming.dateBasis ?: old?.payroll?.dateBasis,
            totalDeductions = incoming.totalDeductions ?: old?.payroll?.totalDeductions,
            lines = incoming.lines.takeIf { it.isNotEmpty() } ?: old?.payroll?.lines.orEmpty(),
            linesComplete = incoming.linesComplete ?: old?.payroll?.linesComplete) } ?: old?.payroll
        val document = fresh?.copy(kind = select("kind", old?.kind, fresh.kind), total = select("total", old?.total, fresh.total), date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(date)), issuer = value.fuente ?: concept, currency = currency, gross = gross, net = net,
            taxes = taxes, vatPercent = value.ivaPercent, withholdingPercent = value.irpfPercent.takeIf { fresh.withholdingPercent != null || it != 0.0 }, category = value.categoria, subcategory = value.subcategoria, number = select("number", old?.number, fresh.number),
            contributionBase = select("contributionBase", old?.contributionBase, fresh.contributionBase),
            socialSecurity = select("socialSecurity", old?.socialSecurity, fresh.socialSecurity),
            payroll = select("payroll", old?.payroll, payroll),
            issuerTaxId = select("issuerTaxId", old?.issuerTaxId, fresh.issuerTaxId), recipientTaxId = select("recipientTaxId", old?.recipientTaxId, fresh.recipientTaxId),
            workerId = select("workerId", old?.workerId, fresh.workerId), payPeriod = select("payPeriod", old?.payPeriod, fresh.payPeriod),
            paymentKind = select("paymentKind", old?.paymentKind, fresh.paymentKind), payrollReference = select("payrollReference", old?.payrollReference, fresh.payrollReference),
            taxBase = select("taxBase", old?.taxBase, fresh.taxBase), vatAmount = select("vatAmount", old?.vatAmount, fresh.vatAmount),
            withholdingAmount = select("withholdingAmount", old?.withholdingAmount, fresh.withholdingAmount), discount = select("discount", old?.discount, fresh.discount),
            country = select("country", old?.country, fresh.country), priceBasis = select("priceBasis", old?.priceBasis, fresh.priceBasis),
            taxesComplete = select("taxesComplete", old?.taxesComplete, fresh.taxesComplete))
        return IncomeEnrichment(value.copy(evidence = mergeEvidence(previous.evidence, value.evidence, document, conflicts, keepExisting)), conflicts.distinct())
    }

    private fun mergeEvidence(old: DocumentEvidence?, fresh: DocumentEvidence?, document: ScannedDocument?, conflicts: List<String>, keepExisting: Boolean): DocumentEvidence? {
        if (fresh == null || document == null) return old
        val oldFields = old?.document?.let { DocumentEvidenceCodec.json.encodeToJsonElement(ScannedDocument.serializer(), it).jsonObject }.orEmpty()
        val mergedFields = DocumentEvidenceCodec.json.encodeToJsonElement(ScannedDocument.serializer(), document).jsonObject
        val keptOrigins = old?.fieldOrigins.orEmpty().filter { (field, _) -> oldFields[field] == mergedFields[field] && (keepExisting || field !in conflicts) }
        return fresh.copy(document = document, fieldOrigins = fresh.fieldOrigins + keptOrigins,
            correctedFields = fresh.correctedFields + old?.correctedFields.orEmpty().filter { keepExisting || it !in conflicts })
    }
}
