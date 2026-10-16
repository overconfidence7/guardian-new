package app.guardianmode.core

/** One line on the parent's "What my family can see" screen. */
data class SharingItem(
    val key: String,
    val title: String,
    val whatIsShared: String,
    val whatIsNeverShared: String,
    val enabled: Boolean,
)

/**
 * Builds the transparency screen from the live configuration, so the parent always
 * sees exactly what is switched on rather than a static privacy text.
 */
object Transparency {

    fun items(config: DeviceConfig): List<SharingItem> {
        val p = config.protection
        return listOf(
            SharingItem(
                key = "pause",
                title = "Pause before sending money",
                whatIsShared = "That a pause card appeared, which app it was for, and whether you chose to call family.",
                whatIsNeverShared = "Anything you type or see inside the app.",
                enabled = p.pauseTripwires.enabled,
            ),
            SharingItem(
                key = "remoteAccess",
                title = "Remote-control app alarm",
                whatIsShared = "The name of a remote-control app when it is installed or opened.",
                whatIsNeverShared = "What happens inside that app.",
                enabled = p.remoteAccessAlarm.enabled,
            ),
            SharingItem(
                key = "longCall",
                title = "Long calls with unknown numbers",
                whatIsShared = "That a call with an unknown number went past ${p.longCallAlert.minutes} minutes.",
                whatIsNeverShared = "Who called, their number, or what was said. Calls are never recorded.",
                enabled = p.longCallAlert.enabled,
            ),
            SharingItem(
                key = "screening",
                title = "Unknown caller screening",
                whatIsShared = "Nothing. Screening happens on your phone only.",
                whatIsNeverShared = "Who calls you.",
                enabled = p.callScreening.mode != ScreeningMode.OFF,
            ),
            SharingItem(
                key = "checkIn",
                title = "Daily \"I'm OK\"",
                whatIsShared = "Whether your phone has been used today, by ${config.checkIn.deadline}.",
                whatIsNeverShared = "Where you are or what you used the phone for.",
                enabled = config.checkIn.enabled,
            ),
            SharingItem(
                key = "help",
                title = "Help me button",
                whatIsShared = "A picture of your screen, only when you press Help and say yes.",
                whatIsNeverShared = "Your screen at any other time.",
                enabled = true,
            ),
            SharingItem(
                key = "reminders",
                title = "Reminders",
                whatIsShared = if (config.reminders.any { it.notifyFamilyIfMissed }) {
                    "Whether you confirmed reminders your family asked to hear about."
                } else {
                    "Nothing."
                },
                whatIsNeverShared = "Your other notifications.",
                enabled = config.reminders.isNotEmpty(),
            ),
        )
    }

    /** Plain-language lines describing what is never collected, shown at the bottom. */
    val NEVER_COLLECTED = listOf(
        "Your messages and texts",
        "What is said on calls",
        "Your location",
        "Your photos, unless you send one with Help",
        "Your passwords or bank details",
    )
}
