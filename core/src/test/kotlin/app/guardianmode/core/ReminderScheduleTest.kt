package app.guardianmode.core

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** qa/antigravity/cases/12-help-and-reminders.md, the scheduling rules (docs/SPEC.md F11, core/ReminderSchedule.kt). */
class ReminderScheduleTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(text: String, z: ZoneId = zone) = ZonedDateTime.of(LocalDateTime.parse(text), z)

    @Test fun gm_help_020_reminderFiresAtTheMinuteItIsSetFor() {
        val now = at("2026-10-20T10:57:12")
        val r = Reminder("r", "Blood pressure pill", detail = "One white tablet with water", times = listOf("11:00"))
        assertEquals(at("2026-10-20T11:00"), ReminderSchedule.next(r, now))
        // After it fires, the scheduler asks for the next one from just after that minute: tomorrow.
        assertEquals(at("2026-10-21T11:00"), ReminderSchedule.next(r, at("2026-10-20T11:00").plusNanos(1_000_000)))
    }

    @Test fun gm_help_020_dailyReminderFiresAtTheNextTimeTodayOrTomorrow() {
        val r = Reminder("r", "Blood pressure pill", times = listOf("20:00", "08:00"))
        assertEquals(at("2026-10-16T08:00"), ReminderSchedule.next(r, at("2026-10-16T07:00")))
        assertEquals(at("2026-10-16T20:00"), ReminderSchedule.next(r, at("2026-10-16T08:00")))
        assertEquals(at("2026-10-17T08:00"), ReminderSchedule.next(r, at("2026-10-16T21:00")))
    }

    @Test fun gm_help_020_soonestReminderAcrossTheListIsScheduledFirst() {
        val a = Reminder("a", "A", times = listOf("20:00"))
        val b = Reminder("b", "B", times = listOf("12:00"))
        val next = ReminderSchedule.nextAcross(listOf(a, b), at("2026-10-16T09:00"))!!
        assertEquals("B", next.reminder.title)
        assertEquals(at("2026-10-16T12:00"), next.at)
        assertNull(ReminderSchedule.nextAcross(emptyList(), at("2026-10-16T09:00")))
    }

    @Test fun gm_help_020_badTimesAreIgnored() {
        assertNull(ReminderSchedule.next(Reminder("r", "x", times = listOf("25:99")), at("2026-10-16T10:00")))
        assertNull(ReminderSchedule.next(Reminder("r", "x", times = emptyList()), at("2026-10-16T10:00")))
        assertEquals(
            at("2026-10-16T12:00"),
            ReminderSchedule.next(Reminder("r", "x", times = listOf("nope", "12:00")), at("2026-10-16T10:00")),
        )
        assertNull(ReminderSchedule.next(Reminder("r", "x", times = listOf("12:00"), date = "2026-13-45"), at("2026-10-16T10:00")))
    }

    @Test fun gm_help_026_todaysListHasEveryRemainingOccurrenceInTimeOrder() {
        val a = Reminder("a", "A", times = listOf("08:00", "20:00"))
        val b = Reminder("b", "B", times = listOf("12:00"))
        val list = ReminderSchedule.upcomingToday(listOf(a, b), at("2026-10-16T09:00"))
        assertEquals(listOf("B", "A"), list.map { it.reminder.title })
        assertEquals(listOf(at("2026-10-16T12:00"), at("2026-10-16T20:00")), list.map { it.at })
        // One due this very minute is still listed.
        assertEquals(listOf("B", "A"), ReminderSchedule.upcomingToday(listOf(a, b), at("2026-10-16T12:00")).map { it.reminder.title })
        assertTrue(ReminderSchedule.upcomingToday(listOf(a, b), at("2026-10-16T21:00")).isEmpty(), "Nothing else today.")
    }

    @Test fun gm_help_026_weekdayAndOneDateRemindersOnlyOnTheirDays() {
        // 2026-10-16 is a Friday, 2026-10-19 a Monday.
        val dialysis = Reminder("d", "Dialysis", times = listOf("09:00"), daysOfWeek = setOf(1, 3))
        val dentist = Reminder("t", "Dentist", kind = ReminderKind.APPOINTMENT, times = listOf("14:30"), date = "2026-10-20")
        val friday = ReminderSchedule.upcomingToday(listOf(dialysis, dentist), at("2026-10-16T00:00"))
        assertTrue(friday.isEmpty(), friday.toString())
        val monday = ReminderSchedule.upcomingToday(listOf(dialysis, dentist), at("2026-10-19T00:00"))
        assertEquals(listOf("Dialysis"), monday.map { it.reminder.title })
        val tuesday = ReminderSchedule.upcomingToday(listOf(dialysis, dentist), at("2026-10-20T00:00"))
        assertEquals(listOf("Dentist"), tuesday.map { it.reminder.title })
    }

    @Test fun gm_help_026_weekdayFilterSkipsOtherDays() {
        val r = Reminder("r", "Dialysis", times = listOf("09:00"), daysOfWeek = setOf(1, 3))
        assertEquals(at("2026-10-19T09:00"), ReminderSchedule.next(r, at("2026-10-16T10:00")))
        // Out-of-range day numbers are ignored rather than breaking the schedule.
        val odd = Reminder("r", "x", times = listOf("09:00"), daysOfWeek = setOf(0, 9))
        assertEquals(at("2026-10-17T09:00"), ReminderSchedule.next(odd, at("2026-10-16T10:00")))
    }

    @Test fun gm_help_026_oneOffAppointmentFiresOnce() {
        val r = Reminder("r", "Dentist", kind = ReminderKind.APPOINTMENT, times = listOf("14:30"), date = "2026-10-20")
        assertEquals(at("2026-10-20T14:30"), ReminderSchedule.next(r, at("2026-10-16T10:00")))
        assertNull(ReminderSchedule.next(r, at("2026-10-20T15:00")))
    }

    @Test fun gm_help_027_afterATimeZoneChangeRemindersFireAtLocalTimeInTheNewZone() {
        val r = Reminder("r", "Pill", times = listOf("08:00"))
        val la = ZoneId.of("America/Los_Angeles")
        val kolkata = ZoneId.of("Asia/Kolkata")
        assertEquals(at("2026-10-21T08:00", la), ReminderSchedule.next(r, at("2026-10-20T21:00", la)))
        val inKolkata = ReminderSchedule.next(r, at("2026-10-20T21:00", kolkata))!!
        assertEquals(at("2026-10-21T08:00", kolkata), inKolkata)
        assertEquals(30, inKolkata.offset.totalSeconds / 60 % 60, "half-hour offset kept")
    }

    @Test fun gm_help_027_daylightSavingGapStillYieldsATime() {
        val r = Reminder("r", "x", times = listOf("02:30"))
        // 2027-03-14 02:30 does not exist in New York; java.time shifts it forward.
        assertEquals(at("2027-03-14T03:30"), ReminderSchedule.next(r, at("2027-03-14T00:00")))
    }

    @Test fun gm_help_027_fallBackHourFiresOnceNotTwice() {
        // 2026-11-01 01:30 happens twice in New York.
        val r = Reminder("r", "x", times = listOf("01:30"))
        val first = ReminderSchedule.next(r, at("2026-11-01T00:00"))!!
        assertEquals(LocalDateTime.parse("2026-11-01T01:30"), first.toLocalDateTime())
        val second = ReminderSchedule.next(r, first)!!
        assertEquals(LocalDateTime.parse("2026-11-02T01:30"), second.toLocalDateTime())
        assertEquals(1, ReminderSchedule.upcomingToday(listOf(r), at("2026-11-01T00:00")).size)
    }

    @Test fun gm_help_027_unknownZoneFallsBackToThePhoneZone() {
        assertEquals(ZoneId.systemDefault(), ReminderSchedule.zoneOf("Not/AZone"))
        assertEquals(ZoneId.of("Asia/Kolkata"), ReminderSchedule.zoneOf("Asia/Kolkata"))
    }
}
