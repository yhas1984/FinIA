package com.gastos.local.database

import androidx.room.TypeConverter
import com.gastos.domain.model.InvoiceType

class Converters {

    @TypeConverter
    fun fromInvoiceType(value: InvoiceType): String = value.name

    @TypeConverter
    fun toInvoiceType(value: String): InvoiceType = InvoiceType.valueOf(value)
}
