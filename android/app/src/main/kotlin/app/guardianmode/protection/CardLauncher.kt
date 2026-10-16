package app.guardianmode.protection

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import app.guardianmode.R
import app.guardianmode.core.GuardianAction
import app.guardianmode.ui.cards.CardRequest
import app.guardianmode.ui.cards.GuardianCardActivity

/**
 * Puts a full-screen card in front of the parent. Android lets an app start an activity
 * from the background when it holds "Display over other apps" (and in practice when it
 * is the home app), so that is the main path. Otherwise a high-priority notification
 * with a full-screen intent stands in, which Android shows as a heads-up banner if the
 * full-screen permission was not granted.
 */
class CardLauncher(private val context: Context, private val capabilities: Capabilities) {

    fun show(request: CardRequest) {
        val intent = GuardianCardActivity.intent(context, request)
        val direct = Settings.canDrawOverlays(context) || capabilities.has(Capability.HOME_APP)
        if (direct) {
            val started = runCatching { context.startActivity(intent) }
                .onFailure { Log.w(TAG, "Direct card launch refused, using notification", it) }
                .isSuccess
            if (started) return
        }
        notify(request, intent)
    }

    fun show(action: GuardianAction) {
        CardRequest.from(action)?.let(::show)
    }

    private fun notify(request: CardRequest, intent: Intent) {
        val pending = PendingIntent.getActivity(
            context, request.kind.ordinal, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_CARDS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(request.notificationTitle)
            .setContentText(request.notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(request.notificationText))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(Notifications.ID_CARD, notification)
    }

    private companion object {
        const val TAG = "CardLauncher"
    }
}
