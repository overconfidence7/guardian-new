package app.guardianmode.ui.transparency

import app.guardianmode.core.EventType
import app.guardianmode.core.GuardianEvent

/** How an alert that went to the family is described to the parent: plain, never alarming. */
object EventText {
    fun forParent(event: GuardianEvent): String {
        val app = event.data["app"] ?: "an app"
        return when (event.type) {
            EventType.LONG_UNKNOWN_CALL -> "You were on a long call (${event.data["minutes"] ?: "20"}+ minutes) with a number you don't know."
            EventType.PAUSE_SHOWN -> "A pause card was shown when you opened $app after a call from an unknown number."
            EventType.PAUSE_CALLED_FAMILY -> "You chose to call family before using $app."
            EventType.PAUSE_CONTINUED -> "You chose to continue to $app."
            EventType.REMOTE_ACCESS_INSTALLED -> "$app was installed. It can let someone control your phone."
            EventType.REMOTE_ACCESS_OPENED -> "$app was opened. It can let someone control your phone."
            EventType.CONTROL_APP_INSTALLED -> "$app was installed. It asks to control your screen."
            EventType.HELP_REQUESTED -> "You asked for help and sent a picture of your screen."
            EventType.REMINDER_MISSED -> "\"${event.data["title"] ?: "A reminder"}\" wasn't marked done."
            EventType.PROTECTION_CHANGED_BY_PARENT -> "You changed a protection setting."
            EventType.PERMISSION_MISSING -> "Something Guardian needs was switched off."
            EventType.CHECK_IN_MISSED -> "Your phone hadn't been used yet today."
        }
    }
}
