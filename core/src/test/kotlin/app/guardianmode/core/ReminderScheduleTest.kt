package app.guardianmode.core

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReminderScheduleTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(text: String) = ZonedDateTime.of(java.time.LocalDateTime.parse(text), zone)

    @Test fun `daily reminder fires at the next time today or tomorrow`() {
        val r = Reminder("r", "Blood pressure pill", times = listOf("20:00", "08:00"))
        assertEquals(at("2026-10-16T08:00"), ReminderSchedule.next(r, at("2026-10-16T07:00")))
        assertEquals(at("2026-10-16T20:00"), ReminderSchedule.next(r, at("2026-10-16T08:00")))
        assertEquals(at("2026-10-17T08:00"), ReminderSchedule.next(r, at("2026-10-16T21:00")))
    }

    @Test fun `weekday filter skips other days`() {
        // 2026-10-16 is a Friday.
        val r = Reminder("r", "Dialysis", times = listOf("09:00"), daysOfWeek = setOf(1, 3))
        assertEquals(at("2026-10-19T09:00"), ReminderSchedule.next(r, at("2026-10-16T10:00")))
    }

    @Test fun `one-off appointment fires once`() {
        val r = Reminder("r", "Dentist", kind = ReminderKind.APPOINTMENT, times = listOf("14:30"), date = "2026-10-20")
        assertEquals(at("2026-10-20T14:30"), ReminderSchedule.next(r, at("2026-10-16T10:00")))
        assertNull(ReminderSchedule.next(r, at("2026-10-20T15:00")))
    }

    @Test fun `bad times are ignored`() {
        assertNull(ReminderSchedule.next(Reminder("r", "x", times = listOf("25:99")), at("2026-10-16T10:00")))
    }

    @Test fun `upcoming today lists every remaining occurrence in order`() {
        val a = Reminder("a", "A", times = listOf("08:00", "20:00"))
        val b = Reminder("b", "B", times = listOf("12:00"))
        val list = ReminderSchedule.upcomingToday(listOf(a, b), at("2026-10-16T09:00"))
        assertEquals(listOf("B", "A"), list.map { it.reminder.title })
    }

    @Test fun `daylight saving gap still yields a time`() {
        val r = Reminder("r", "x", times = listOf("02:30"))
        // 2027-03-14 02:30 does not exist in New York; java.time shifts it forward.
        assertEquals(at("2027-03-14T03:30"), ReminderSchedule.next(r, at("2027-03-14T00:00")))
    }
}
