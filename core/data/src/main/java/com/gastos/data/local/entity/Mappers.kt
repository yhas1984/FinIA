package com.gastos.data.local.entity

import com.gastos.domain.model.*

fun InvoiceEntity.toDomain(): Invoice = Invoice(
    id = id,
    fecha = fecha,
    proveedor = proveedor,
    tipo = tipo,
    moneda = moneda,
    total = total,
    baseImponible = baseImponible,
    ivaImporte = ivaImporte,
    ivaPercent = ivaPercent,
    irpfPercent = irpfPercent,
    paisCodigo = paisCodigo,
    nifEmisor = nifEmisor,
    nifReceptor = nifReceptor,
    imagenUri = imagenUri,
    ocrRawText = ocrRawText,
    notas = notas,
    categoria = categoria,
    ingresoDevengado = ingresoDevengado,
    ingresoDeducciones = ingresoDeducciones,
    ingresoTipo = ingresoTipo,
    conceptoIngreso = conceptoIngreso,
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
    baseImponible = baseImponible,
    ivaImporte = ivaImporte,
    ivaPercent = ivaPercent,
    irpfPercent = irpfPercent,
    paisCodigo = paisCodigo,
    nifEmisor = nifEmisor,
    nifReceptor = nifReceptor,
    imagenUri = imagenUri,
    ocrRawText = ocrRawText,
    notas = notas,
    categoria = categoria,
    ingresoDevengado = ingresoDevengado,
    ingresoDeducciones = ingresoDeducciones,
    ingresoTipo = ingresoTipo,
    conceptoIngreso = conceptoIngreso,
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
    categoriaId = categoriaId,
    categoria = categoria,
    comercio = comercio,
    fechaCompra = fechaCompra,
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
    categoriaId = categoriaId,
    categoria = categoria,
    comercio = comercio,
    fechaCompra = fechaCompra,
    createdAt = createdAt
)

fun IncomeEntity.toDomain(): Income = Income(
    id = id,
    fecha = fecha,
    concepto = concepto,
    monto = monto,
    totalDevengado = totalDevengado,
    totalDeducciones = totalDeducciones,
    totalNeto = totalNeto,
    moneda = moneda,
    tipoIngreso = tipoIngreso,
    fuente = fuente,
    ivaPercent = ivaPercent,
    irpfPercent = irpfPercent,
    imagenUri = imagenUri,
    notas = notas,
    categoria = categoria,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Income.toEntity(): IncomeEntity = IncomeEntity(
    id = id,
    fecha = fecha,
    concepto = concepto,
    monto = monto,
    totalDevengado = totalDevengado,
    totalDeducciones = totalDeducciones,
    totalNeto = totalNeto,
    moneda = moneda,
    tipoIngreso = tipoIngreso,
    fuente = fuente,
    ivaPercent = ivaPercent,
    irpfPercent = irpfPercent,
    imagenUri = imagenUri,
    notas = notas,
    categoria = categoria,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun CategoryEntity.toDomain(): Category = Category(
    id = id,
    nombre = nombre,
    icono = icono,
    color = color,
    esDefault = esDefault,
    createdAt = createdAt
)

fun Category.toEntity(): CategoryEntity = CategoryEntity(
    id = id,
    nombre = nombre,
    icono = icono,
    color = color,
    esDefault = esDefault,
    createdAt = createdAt
)

fun ExchangeRateEntity.toDomain(): ExchangeRate = ExchangeRate(
    id = id,
    monedaOrigen = monedaOrigen,
    monedaDestino = monedaDestino,
    tasa = tasa,
    fecha = fecha
)

fun ExchangeRate.toEntity(): ExchangeRateEntity = ExchangeRateEntity(
    id = id,
    monedaOrigen = monedaOrigen,
    monedaDestino = monedaDestino,
    tasa = tasa,
    fecha = fecha
)

fun CountryFiscalConfigEntity.toDomain(): CountryFiscalConfig = CountryFiscalConfig(
    paisCodigo = paisCodigo,
    nombrePais = nombrePais,
    ivaRates = ivaRates.removeSurrounding("[", "]").split(",").mapNotNull { it.trim().toDoubleOrNull() },
    irpfRate = irpfRate,
    nifFormat = nifFormat,
    nombreLeyFiscal = nombreLeyFiscal
)

fun CountryFiscalConfig.toEntity(): CountryFiscalConfigEntity = CountryFiscalConfigEntity(
    paisCodigo = paisCodigo,
    nombrePais = nombrePais,
    ivaRates = ivaRates.joinToString(",", "[", "]"),
    irpfRate = irpfRate,
    nifFormat = nifFormat,
    nombreLeyFiscal = nombreLeyFiscal
)