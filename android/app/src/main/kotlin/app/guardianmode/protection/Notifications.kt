package app.guardianmode.protection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.guardianmode.R
import app.guardianmode.ui.MainActivity
import app.guardianmode.ui.help.HelpActivity

object Notifications {
    const val CHANNEL_PROTECTION = "protection"
    const val CHANNEL_CARDS = "cards"
    const val CHANNEL_REMINDERS = "reminders"
    const val CHANNEL_FAMILY = "family"

    const val ID_PROTECTION = 1
    const val ID_CARD = 2
    const val ID_HELP_READY = 3
    const val ID_REMINDER_BASE = 1000

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_PROTECTION, context.getString(R.string.channel_protection), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.channel_protection_desc)
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_CARDS, context.getString(R.string.channel_cards), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.channel_cards_desc)
                },
                NotificationChannel(CHANNEL_REMINDERS, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.channel_reminders_desc)
                },
                NotificationChannel(CHANNEL_FAMILY, context.getString(R.string.channel_family), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.channel_family_desc)
                },
            ),
        )
    }

    /** The persistent "Guardian Mode is on" notification Play requires while protection runs. */
    fun protection(context: Context, paused: Boolean): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DEST_TRANSPARENCY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val help = PendingIntent.getActivity(
            context, 1, Intent(context, HelpActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggle = PendingIntent.getBroadcast(
            context, 2,
            Intent(context, NotificationActionReceiver::class.java).setAction(
                if (paused) NotificationActionReceiver.ACTION_RESUME else NotificationActionReceiver.ACTION_PAUSE_HOUR,
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_PROTECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (paused) "Guardian Mode is paused" else "Guardian Mode is on")
            .setContentText(if (paused) "Protection is paused for a little while." else "Your family is in your corner. Tap to see what they can see.")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Ask for help", help)
            .addAction(0, if (paused) "Turn back on" else "Pause for 1 hour", toggle)
            .build()
    }
}
