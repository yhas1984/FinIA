package com.gastos.data.local.entity

import com.gastos.domain.model.*

fun InvoiceEntity.toDomain(): Invoice = Invoice(
    taxes = DocumentTaxCodec.decode(taxesJson),
    evidence = DocumentEvidenceCodec.decode(evidenceJson),
    documentUuid = documentUuid,
    driveAccountId = driveAccountId,
    driveContentHash = driveContentHash,
    driveSyncError = driveSyncError,
    id = id,
    fecha = fecha,
    proveedor = proveedor,
    tipo = tipo,
    categoria = TransactionCategories.normalizeCategory(categoria),
    subcategoria = TransactionCategories.normalizeCategory(subcategoria),
    moneda = moneda,
    total = total,
    numeroFactura = numeroFactura,
    baseImponible = baseImponible,
    cuotaIva = cuotaIva,
    ivaPercent = ivaPercent,
    irpfPercent = irpfPercent,
    paisCodigo = paisCodigo,
    nifEmisor = nifEmisor,
    nifReceptor = nifReceptor,
    imagenUri = imagenUri,
    driveFileId = driveFileId,
    driveWebViewLink = driveWebViewLink,
    driveUploadPending = driveUploadPending,
    ocrRawText = ocrRawText,
    notas = notas,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Invoice.toEntity(): InvoiceEntity = InvoiceEntity(
    taxesJson = DocumentTaxCodec.encode(taxes),
    evidenceJson = evidence?.let(DocumentEvidenceCodec::encode),
    documentKey = documentIdentity().key,
    sourceSha256 = evidence?.sourceSha256,
    documentUuid = documentUuid,
    driveAccountId = driveAccountId,
    driveContentHash = driveContentHash,
    driveSyncError = driveSyncError,
    id = id,
    fecha = fecha,
    proveedor = proveedor,
    tipo = tipo,
    categoria = TransactionCategories.normalizeCategory(categoria),
    subcategoria = TransactionCategories.normalizeCategory(subcategoria),
    moneda = moneda,
    total = total,
    numeroFactura = numeroFactura?.trim()?.takeIf { it.isNotBlank() },
    baseImponible = baseImponible,
    cuotaIva = cuotaIva,
    ivaPercent = ivaPercent,
    irpfPercent = irpfPercent,
    paisCodigo = paisCodigo,
    nifEmisor = nifEmisor,
    nifReceptor = nifReceptor,
    imagenUri = imagenUri,
    driveFileId = driveFileId,
    driveWebViewLink = driveWebViewLink,
    driveUploadPending = driveUploadPending,
    ocrRawText = ocrRawText,
    notas = notas,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun ProductEntity.toDomain(): Product = Product(
    taxes = DocumentTaxCodec.decode(taxesJson),
    pricesIncludeTax = pricesIncludeTax,
    id = id,
    invoiceId = invoiceId,
    descripcion = descripcion,
    cantidad = cantidad,
    precioUnitario = precioUnitario,
    subtotal = subtotal,
    ivaPercent = ivaPercent,
    ivaAmount = ivaAmount,
    createdAt = createdAt
)

fun Product.toEntity(): ProductEntity = ProductEntity(
    taxesJson = DocumentTaxCodec.encode(taxes),
    pricesIncludeTax = pricesIncludeTax,
    id = id,
    invoiceId = invoiceId,
    descripcion = descripcion,
    cantidad = cantidad,
    precioUnitario = precioUnitario,
    subtotal = subtotal,
    ivaPercent = ivaPercent,
    ivaAmount = ivaAmount,
    createdAt = createdAt
)

fun IncomeEntity.toDomain(): Income = Income(
    taxes = DocumentTaxCodec.decode(taxesJson),
    evidence = DocumentEvidenceCodec.decode(evidenceJson),
    documentUuid = documentUuid,
    driveAccountId = driveAccountId,
    driveContentHash = driveContentHash,
    driveSyncError = driveSyncError,
    driveFileId = driveFileId,
    driveWebViewLink = driveWebViewLink,
    driveUploadPending = driveUploadPending,
    id = id,
    fecha = fecha,
    concepto = concepto,
    monto = monto,
    totalDevengado = totalDevengado,
    totalNeto = totalNeto,
    moneda = moneda,
    fuente = fuente,
    categoria = TransactionCategories.normalizeCategory(categoria),
    subcategoria = TransactionCategories.normalizeCategory(subcategoria),
    ivaPercent = ivaPercent,
    irpfPercent = irpfPercent,
    imagenUri = imagenUri,
    notas = notas,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Income.toEntity(): IncomeEntity = IncomeEntity(
    taxesJson = DocumentTaxCodec.encode(taxes),
    evidenceJson = evidence?.let(DocumentEvidenceCodec::encode),
    documentKey = documentIdentity().key,
    sourceSha256 = evidence?.sourceSha256,
    documentUuid = documentUuid,
    driveAccountId = driveAccountId,
    driveContentHash = driveContentHash,
    driveSyncError = driveSyncError,
    driveFileId = driveFileId,
    driveWebViewLink = driveWebViewLink,
    driveUploadPending = driveUploadPending,
    id = id,
    fecha = fecha,
    concepto = concepto,
    monto = monto,
    totalDevengado = totalDevengado,
    totalNeto = totalNeto,
    moneda = moneda,
    fuente = fuente,
    categoria = TransactionCategories.normalizeCategory(categoria),
    subcategoria = TransactionCategories.normalizeCategory(subcategoria),
    ivaPercent = ivaPercent,
    irpfPercent = irpfPercent,
    imagenUri = imagenUri,
    notas = notas,
    createdAt = createdAt,
    updatedAt = updatedAt
)


/**
 * Parsea `ivaRates` tolerando tanto el formato actual CSV ("21,10,4") como
 * el legado con corchetes ("[21,10,4]") que escribían versiones antiguas.
 * Los valores no numéricos se descartan; si no queda ninguno se cae en la
 * tarifa estándar española.
 */
private fun parseIvaRates(raw: String): List<Double> =
    raw.trim()
        .removePrefix("[")
        .removeSuffix("]")
        .split(",")
        .mapNotNull { it.trim().toDoubleOrNull() }
        .ifEmpty { listOf(21.0) }

fun CountryFiscalConfigEntity.toDomain(): CountryFiscalConfig = CountryFiscalConfig(
    paisCodigo = paisCodigo,
    nombrePais = nombrePais,
    ivaRates = parseIvaRates(ivaRates),
    irpfRate = irpfRate,
    nifFormat = nifFormat,
    nombreLeyFiscal = nombreLeyFiscal
)

fun CountryFiscalConfig.toEntity(): CountryFiscalConfigEntity = CountryFiscalConfigEntity(
    paisCodigo = paisCodigo,
    nombrePais = nombrePais,
    ivaRates = ivaRates.joinToString(","),
    irpfRate = irpfRate,
    nifFormat = nifFormat,
    nombreLeyFiscal = nombreLeyFiscal
)
