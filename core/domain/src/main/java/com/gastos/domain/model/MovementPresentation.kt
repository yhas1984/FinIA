package com.gastos.domain.model

import java.time.Instant
import java.time.ZoneId

/** Storage identity, independent from recyclable local numeric identifiers. */
enum class MovementSource { INVOICE, INCOME }
data class MovementReference(val source: MovementSource, val uuid: String)
fun Invoice.movementReference() = MovementReference(MovementSource.INVOICE, documentUuid)
fun Income.movementReference() = MovementReference(if (id < 0) MovementSource.INVOICE else MovementSource.INCOME, documentUuid)

fun isWithinPeriod(timestamp: Long, start: Long?, end: Long?, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    val day = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
    return (start == null || day >= Instant.ofEpochMilli(start).atZone(zone).toLocalDate()) &&
        (end == null || day <= Instant.ofEpochMilli(end).atZone(zone).toLocalDate())
}
