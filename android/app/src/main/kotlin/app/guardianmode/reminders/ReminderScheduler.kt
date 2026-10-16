package app.guardianmode.reminders

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import app.guardianmode.GuardianApp
import app.guardianmode.R
import app.guardianmode.core.EventType
import app.guardianmode.core.GuardianEvent
import app.guardianmode.core.ReminderSchedule
import app.guardianmode.core.Severity
import app.guardianmode.data.LocalStore
import app.guardianmode.protection.Notifications
import app.guardianmode.protection.CardLauncher
import app.guardianmode.ui.cards.CardKind
import app.guardianmode.ui.cards.CardRequest
import app.guardianmode.ui.cards.GuardianCardActivity
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Keeps exactly one alarm set: the next reminder across all of them. Uses exact alarms
 * when the parent allowed them, and a 5-minute window otherwise. Medication reminders
 * are not an "alarm clock" use, so Guardian never requests USE_EXACT_ALARM.
 */
class ReminderScheduler(private val context: Context, private val store: LocalStore) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun reschedule(now: Long = System.currentTimeMillis()) {
        val config = store.current.config
        val zone = ReminderSchedule.zoneOf(config.checkIn.timezone)
        val after = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val pending = alarmIntent(null, 0)
        alarms.cancel(pending)
        val next = ReminderSchedule.nextAcross(config.reminders, after) ?: return
        val at = next.at.toInstant().toEpochMilli()
        val intent = alarmIntent(next.reminder.id, at)
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW, intent)
        }
    }

    fun scheduleMissedCheck(reminderId: String, at: Long) {
        val check = PendingIntent.getBroadcast(
            context, (reminderId + at).hashCode(),
            Intent(context, ReminderReceiver::class.java)
                .setAction(ReminderReceiver.ACTION_CHECK)
                .putExtra(ReminderReceiver.EXTRA_ID, reminderId)
                .putExtra(ReminderReceiver.EXTRA_AT, at),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at + MISSED_AFTER, check)
    }

    fun snooze(request: CardRequest, minutes: Int = 10) {
        val intent = PendingIntent.getBroadcast(
            context, request.occurrenceKey.hashCode(),
            Intent(context, ReminderReceiver::class.java)
                .setAction(ReminderReceiver.ACTION_SNOOZED)
                .putExtra(ReminderReceiver.EXTRA_ID, request.reminderId)
                .putExtra(ReminderReceiver.EXTRA_AT, request.reminderAt),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + minutes * 60_000L, intent)
    }

    suspend fun markDone(request: CardRequest) {
        store.update { s ->
            val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60_000L
            s.copy(remindersDone = s.remindersDone.filterValues { it > cutoff } + (request.occurrenceKey to System.currentTimeMillis()))
        }
        context.getSystemService(NotificationManager::class.java).cancel(Notifications.ID_REMINDER_BASE + request.reminderId.hashCode() % 1000)
    }

    private fun alarmIntent(reminderId: String?, at: Long): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_FIRE)
            .putExtra(ReminderReceiver.EXTRA_ID, reminderId)
            .putExtra(ReminderReceiver.EXTRA_AT, at),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val WINDOW = 5 * 60_000L
        const val MISSED_AFTER = 60 * 60_000L
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as GuardianApp).container
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val at = intent.getLongExtra(EXTRA_AT, 0)
        val reminder = container.store.current.config.reminders.firstOrNull { it.id == id }
        val pending = goAsync()
        container.appScope.launch {
            try {
                when (intent.action) {
                    ACTION_FIRE, ACTION_SNOOZED -> {
                        if (intent.action == ACTION_FIRE) container.reminders.reschedule(at + 1)
                        if (reminder == null) return@launch
                        val request = CardRequest(
                            kind = CardKind.REMINDER,
                            reminderId = reminder.id,
                            reminderTitle = reminder.title,
                            reminderDetail = reminder.detail,
                            reminderAt = at,
                        )
                        showReminder(context, container.cards, request)
                        if (intent.action == ACTION_FIRE && reminder.notifyFamilyIfMissed) {
                            container.reminders.scheduleMissedCheck(reminder.id, at)
                        }
                    }
                    ACTION_CHECK -> {
                        if (reminder == null) return@launch
                        val done = container.store.current.remindersDone.containsKey("$id@$at")
                        if (!done && !container.store.current.isPaused(System.currentTimeMillis())) {
                            container.store.recordShared(
                                GuardianEvent(
                                    id = UUID.randomUUID().toString(),
                                    type = EventType.REMINDER_MISSED,
                                    at = System.currentTimeMillis(),
                                    severity = Severity.NOTICE,
                                    data = mapOf("title" to reminder.title.take(80)),
                                ),
                            )
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun showReminder(context: Context, cards: CardLauncher, request: CardRequest) {
        // A regular notification always, so the reminder survives being swiped away from the card.
        val open = PendingIntent.getActivity(
            context, request.occurrenceKey.hashCode(), GuardianCardActivity.intent(context, request),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(request.reminderTitle)
            .setContentText(request.reminderDetail.ifBlank { "Tap when it's done." })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(Notifications.ID_REMINDER_BASE + request.reminderId.hashCode() % 1000, notification)
        cards.show(request)
    }

    companion object {
        const val ACTION_FIRE = "app.guardianmode.REMINDER_FIRE"
        const val ACTION_SNOOZED = "app.guardianmode.REMINDER_SNOOZED"
        const val ACTION_CHECK = "app.guardianmode.REMINDER_CHECK"
        const val EXTRA_ID = "id"
        const val EXTRA_AT = "at"
    }
}
