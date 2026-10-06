package com.gastos.feature.backup

internal fun managedSheetDefinitions(locale: SheetsSchema.LocaleCode): List<ManagedSheet> {
    val descriptor = SheetsSchema.descriptor(locale)
    val spanish = locale == SheetsSchema.LocaleCode.ES
    val taxTitle = if (spanish) "Impuestos" else "Taxes"
    return listOf(
        ManagedSheet(descriptor.recibidasTitle, descriptor.recibidasHeaders, 14, setOf(1), setOf(18), setOf(5,6,7,8,9,10,16), setOf(6,9)),
        ManagedSheet(descriptor.ingresosTitle, descriptor.ingresosHeaders, 10, setOf(1), setOf(15), setOf(2,3,4,5,11,12,13), setOf(5)),
        ManagedSheet(descriptor.productosTitle, descriptor.productosHeaders, 8, setOf(17), setOf(13), setOf(1,2,3,4,5,9,10,11), setOf(4)),
        ManagedSheet(taxTitle, if (spanish) listOf("UUID", "Documento UUID", "Tipo", "Ámbito", "Impuesto", "Porcentaje", "Base original", "Cuota original", "Moneda", "Tratamiento", "Efecto", "Mes", "Año")
            else listOf("UUID", "Document UUID", "Kind", "Scope", "Tax", "Rate", "Original base", "Original amount", "Currency", "Treatment", "Effect", "Month", "Year"), 0, numberColumns = setOf(5,6,7), percentColumns = setOf(5)))

}
