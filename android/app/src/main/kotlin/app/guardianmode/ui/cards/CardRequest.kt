package app.guardianmode.ui.cards

import app.guardianmode.core.AppCategory
import app.guardianmode.core.GuardianAction
import kotlinx.serialization.Serializable

enum class CardKind { PAUSE, REMOTE_ACCESS, SAFE_WORD, REMINDER, LOCKED }

/** Everything a full-screen card needs, passed to the card activity as JSON. */
@Serializable
data class CardRequest(
    val kind: CardKind,
    val packageName: String = "",
    val appLabel: String = "",
    val category: AppCategory? = null,
    val duringCall: Boolean = false,
    val minutesSinceCall: Int = 0,
    val justInstalled: Boolean = false,
    val knownRemoteAccessApp: Boolean = true,
    val reminderId: String = "",
    val reminderTitle: String = "",
    val reminderDetail: String = "",
    val reminderAt: Long = 0,
) {
    val notificationTitle: String
        get() = when (kind) {
            CardKind.PAUSE -> "Hold on a minute"
            CardKind.REMOTE_ACCESS -> if (knownRemoteAccessApp) "This app can control your phone" else "A new app can control your screen"
            CardKind.SAFE_WORD -> "Remember your family safe word"
            CardKind.REMINDER -> reminderTitle
            CardKind.LOCKED -> "$appLabel is locked"
        }

    val notificationText: String
        get() = when (kind) {
            CardKind.PAUSE -> "Did someone on the phone ask you to open $appLabel? Tap to talk it over with family first."
            CardKind.REMOTE_ACCESS -> "Real companies never ask you to install $appLabel. Tap for what to do."
            CardKind.SAFE_WORD -> "If a caller said they're family and in trouble, ask for the safe word."
            CardKind.REMINDER -> reminderDetail.ifBlank { "Tap when it's done." }
            CardKind.LOCKED -> "Enter the code to open it."
        }

    /** Key that identifies one reminder occurrence, used to record "Done". */
    val occurrenceKey: String get() = "$reminderId@$reminderAt"

    companion object {
        fun from(action: GuardianAction): CardRequest? = when (action) {
            is GuardianAction.ShowPauseCard -> CardRequest(
                kind = CardKind.PAUSE,
                packageName = action.packageName,
                appLabel = action.appLabel,
                category = action.category,
                duringCall = action.duringCall,
                minutesSinceCall = action.minutesSinceCall,
            )
            is GuardianAction.ShowRemoteAccessWarning -> CardRequest(
                kind = CardKind.REMOTE_ACCESS,
                packageName = action.packageName,
                appLabel = action.appLabel,
                justInstalled = action.justInstalled,
                knownRemoteAccessApp = action.knownRemoteAccessApp,
            )
            GuardianAction.ShowSafeWordCard -> CardRequest(kind = CardKind.SAFE_WORD)
            is GuardianAction.Share -> null
        }
    }
}
