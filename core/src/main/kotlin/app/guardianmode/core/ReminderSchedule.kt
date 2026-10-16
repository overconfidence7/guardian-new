package app.guardianmode.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class ReminderOccurrence(val reminder: Reminder, val at: ZonedDateTime)

/** Works out when each medication or appointment reminder fires next. */
object ReminderSchedule {

    fun parseTime(text: String): LocalTime? = runCatching { LocalTime.parse(text) }.getOrNull()

    fun next(reminder: Reminder, after: ZonedDateTime): ZonedDateTime? {
        val times = reminder.times.mapNotNull(::parseTime).sorted()
        if (times.isEmpty()) return null
        val zone = after.zone
        reminder.date?.let { dateText ->
            val date = runCatching { LocalDate.parse(dateText) }.getOrNull() ?: return null
            return times.map { ZonedDateTime.of(date, it, zone) }.firstOrNull { it.isAfter(after) }
        }
        val days = reminder.daysOfWeek.filter { it in 1..7 }.map(DayOfWeek::of).toSet()
        for (offset in 0..7) {
            val date = after.toLocalDate().plusDays(offset.toLong())
            if (days.isNotEmpty() && date.dayOfWeek !in days) continue
            times.map { ZonedDateTime.of(date, it, zone) }.firstOrNull { it.isAfter(after) }?.let { return it }
        }
        return null
    }

    fun nextAcross(reminders: List<Reminder>, after: ZonedDateTime): ReminderOccurrence? =
        reminders.mapNotNull { r -> next(r, after)?.let { ReminderOccurrence(r, it) } }.minByOrNull { it.at }

    fun upcomingToday(reminders: List<Reminder>, now: ZonedDateTime): List<ReminderOccurrence> {
        val endOfDay = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
        return reminders.flatMap { r ->
            generateSequence(next(r, now.minusSeconds(1))) { next(r, it) }
                .takeWhile { it.isBefore(endOfDay) }
                .map { ReminderOccurrence(r, it) }
                .toList()
        }.sortedBy { it.at }
    }

    fun zoneOf(id: String): ZoneId = runCatching { ZoneId.of(id) }.getOrDefault(ZoneId.systemDefault())
}
