package com.gastos.feature.settings

import java.util.Calendar
import java.util.TimeZone

/** Horarios de los recordatorios diarios (hora local del dispositivo). */
enum class ReminderSlot(val hour: Int, val minute: Int) {
    MIDDAY(13, 30),
    EVENING(20, 30)
}

/**
 * Milisegundos que faltan hasta la próxima ocurrencia de [hour]:[minute] en [zone].
 * Si la hora de hoy ya pasó (o es exactamente ahora), devuelve la de mañana.
 */
fun delayUntilNext(hour: Int, minute: Int, nowMillis: Long, zone: TimeZone): Long {
    val target = Calendar.getInstance(zone).apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (target.timeInMillis <= nowMillis) target.add(Calendar.DAY_OF_YEAR, 1)
    return target.timeInMillis - nowMillis
}

fun ReminderSlot.delayUntilNext(
    nowMillis: Long = System.currentTimeMillis(),
    zone: TimeZone = TimeZone.getDefault()
): Long = delayUntilNext(hour, minute, nowMillis, zone)
