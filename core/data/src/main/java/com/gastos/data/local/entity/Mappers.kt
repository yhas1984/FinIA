package com.gastos.data.local.entity

import com.gastos.domain.model.*

fun InvoiceEntity.toDomain(): Invoice = Invoice(
    id = id,
    fecha = fecha,
    proveedor = proveedor,
    tipo = tipo,
    moneda = moneda,
    total = total,
    ivaPercent = ivaPercent,
    irpfPercent = irpfPercent,
    paisCodigo = paisCodigo,
    nifEmisor = nifEmisor,
    nifReceptor = nifReceptor,
    imagenUri = imagenUri,
    ocrRawText = ocrRawText,
    notas = notas,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Invoice.toEntity(): InvoiceEntity = InvoiceEntity(
    id = id,
    fecha = fecha,
    proveedor = proveedor,
    tipo = tipo,
    moneda = moneda,
    total = total,
    ivaPercent = ivaPercent,
    irpfPercent = irpfPercent,
    paisCodigo = paisCodigo,
    nifEmisor = nifEmisor,
    nifReceptor = nifReceptor,
    imagenUri = imagenUri,
    ocrRawText = ocrRawText,
    notas = notas,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun ProductEntity.toDomain(): Product = Product(
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
    id = id,
    fecha = fecha,
    concepto = concepto,
    monto = monto,
    totalDevengado = totalDevengado,
    totalNeto = totalNeto,
    moneda = moneda,
    fuente = fuente,
    ivaPercent = ivaPercent,
    irpfPercent = irpfPercent,
    imagenUri = imagenUri,
    notas = notas,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Income.toEntity(): IncomeEntity = IncomeEntity(
    id = id,
    fecha = fecha,
    concepto = concepto,
    monto = monto,
    totalDevengado = totalDevengado,
    totalNeto = totalNeto,
    moneda = moneda,
    fuente = fuente,
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
