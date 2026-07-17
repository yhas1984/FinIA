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


fun CountryFiscalConfigEntity.toDomain(): CountryFiscalConfig = CountryFiscalConfig(
    paisCodigo = paisCodigo,
    nombrePais = nombrePais,
    ivaRates = ivaRates.split(",").mapNotNull { it.trim().toDoubleOrNull() }.ifEmpty { listOf(21.0) },
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
