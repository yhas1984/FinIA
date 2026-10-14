package com.gastos.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ReminderScheduleTest {

    private val zone: TimeZone = TimeZone.getTimeZone("Europe/Madrid")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `antes de la hora objetivo programa para hoy`() {
        val now = at(2026, 10, 13, 12, 0)
        assertEquals(90 * 60_000L, delayUntilNext(13, 30, now, zone))
    }

    @Test
    fun `despues de la hora objetivo salta al dia siguiente`() {
        val now = at(2026, 10, 13, 21, 0)
        val expected = ((23 * 60) + 30) * 60_000L
        assertEquals(expected, delayUntilNext(20, 30, now, zone))
    }

    @Test
    fun `exactamente en la hora objetivo programa para el dia siguiente`() {
        val now = at(2026, 10, 13, 20, 30)
        assertEquals(24 * 60 * 60_000L, delayUntilNext(20, 30, now, zone))
    }

    @Test
    fun `el slot de la noche usa su hora configurada`() {
        val now = at(2026, 10, 13, 20, 0)
        assertEquals(30 * 60_000L, ReminderSlot.EVENING.delayUntilNext(now, zone))
    }

    @Test
    fun `el slot del mediodia calcula desde su propia hora`() {
        val now = at(2026, 10, 13, 9, 0)
        assertEquals(((4 * 60) + 30) * 60_000L, ReminderSlot.MIDDAY.delayUntilNext(now, zone))
    }
}
