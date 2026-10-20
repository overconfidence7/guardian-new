package app.guardianmode.protection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import app.guardianmode.GuardianApp
import app.guardianmode.core.PhoneCallState
import kotlinx.coroutines.launch

/** Call state changes. Gives ringing/off-hook/idle only; Guardian never reads the number here. */
class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> PhoneCallState.RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> PhoneCallState.OFFHOOK
            TelephonyManager.EXTRA_STATE_IDLE -> PhoneCallState.IDLE
            else -> return
        }
        val container = (context.applicationContext as GuardianApp).container
        container.brain.onCallState(state)
        // Make sure the watcher is running while a call is live so it can see apps opening.
        GuardianService.start(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val container = (context.applicationContext as GuardianApp).container
                if (container.store.current.onboardingDone) GuardianService.start(context)
                container.reminders.reschedule()
            }
        }
    }
}

/** Buttons on the persistent notification. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as GuardianApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                // Pausing isn't handled here: it goes through MainActivity so the lock code is asked for.
                if (intent.action == ACTION_RESUME) container.protection.resume()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_RESUME = "app.guardianmode.RESUME"
    }
}
