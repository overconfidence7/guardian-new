package app.guardianmode.core

import kotlin.math.max

/** Something the phone should do in response to what just happened. */
sealed interface GuardianAction {
    /** Full-screen "Did someone on the phone tell you to do this?" card. */
    data class ShowPauseCard(
        val category: AppCategory,
        val packageName: String,
        val appLabel: String,
        val duringCall: Boolean,
        val minutesSinceCall: Int,
    ) : GuardianAction

    /** Full-screen warning that a remote-control app was installed or opened. */
    data class ShowRemoteAccessWarning(
        val packageName: String,
        val appLabel: String,
        val justInstalled: Boolean,
        val knownRemoteAccessApp: Boolean,
    ) : GuardianAction

    /** Gentle card after an unknown call: "Ask for the family safe word." */
    data object ShowSafeWordCard : GuardianAction

    /** Share an event with the family (and show it on the parent's transparency screen). */
    data class Share(val event: GuardianEvent) : GuardianAction
}

enum class PauseOutcome { CALLED_FAMILY, CONTINUED, CLOSED }

/**
 * The scam-friction brain. It is a deterministic state machine fed with timestamps,
 * so every rule is unit-testable without Android. The Android layer feeds it call
 * updates, foreground-app changes, installs and a periodic tick, and carries out the
 * actions it returns.
 */
class TripwireEngine(
    private val config: () -> DeviceConfig,
    private val catalog: AppCatalog = AppCatalog(),
    private val newId: () -> String,
) {
    private var lastUnknownCallEndedAt: Long? = null
    private var activeUnknown: CallSession? = null
    private var longCallShared = false
    private var riskyAppOpenedDuringCall = false
    private val pauseShownAt = mutableMapOf<AppCategory, Long>()
    private val continuedPackages = mutableSetOf<String>()
    private val remoteWarnedAt = mutableMapOf<String, Long>()
    private var foregroundPackage: String? = null

    private val protection get() = config().protection

    /** True while a scam conversation could plausibly be steering the parent. */
    fun isArmed(now: Long): Boolean {
        if (activeUnknown?.answered == true) return true
        val ended = lastUnknownCallEndedAt ?: return false
        return now - ended <= protection.pauseTripwires.windowMinutes * MINUTE
    }

    fun onCall(update: CallUpdate, now: Long): List<GuardianAction> {
        val session = update.session
        if (!session.unknown) return emptyList()
        return when (update) {
            is CallUpdate.Started -> {
                activeUnknown = session
                longCallShared = false
                riskyAppOpenedDuringCall = false
                emptyList()
            }
            is CallUpdate.Answered -> {
                activeUnknown = session
                // A new conversation re-arms every tripwire, even ones dismissed earlier.
                pauseShownAt.clear()
                continuedPackages.clear()
                emptyList()
            }
            is CallUpdate.Ended -> {
                activeUnknown = null
                if (!session.answered) return emptyList()
                lastUnknownCallEndedAt = session.endedAt ?: now
                val actions = mutableListOf<GuardianAction>()
                actions += longCallActionIfDue(session, now)
                if (protection.safeWord.enabled && session.talkMillis(now) >= SAFE_WORD_MIN_TALK) {
                    actions += GuardianAction.ShowSafeWordCard
                }
                actions
            }
        }
    }

    /** Called every few seconds while a call is active, for the long-call alert. */
    fun onTick(now: Long): List<GuardianAction> {
        val call = activeUnknown ?: return emptyList()
        return longCallActionIfDue(call, now)
    }

    fun onForeground(packageName: String, appLabel: String, now: Long): List<GuardianAction> {
        val previous = foregroundPackage
        foregroundPackage = packageName
        if (previous == packageName) return emptyList()

        val p = protection
        val armed = isArmed(now)
        val duringCall = activeUnknown?.answered == true

        if (p.remoteAccessAlarm.enabled && catalog.isRemoteAccess(packageName, p)) {
            if (duringCall) riskyAppOpenedDuringCall = true
            val last = remoteWarnedAt[packageName]
            if (last != null && now - last < REMOTE_REWARN) return emptyList()
            remoteWarnedAt[packageName] = now
            return listOf(
                GuardianAction.ShowRemoteAccessWarning(packageName, appLabel, justInstalled = false, knownRemoteAccessApp = true),
                share(
                    EventType.REMOTE_ACCESS_OPENED,
                    if (armed) Severity.URGENT else Severity.NOTICE,
                    now,
                    "app" to appLabel,
                    "afterUnknownCall" to armed.toString(),
                ),
            )
        }

        val pause = p.pauseTripwires
        if (!pause.enabled || !armed) return emptyList()
        val category = catalog.categoryOf(packageName, p) ?: return emptyList()
        if (category == AppCategory.REMOTE_ACCESS || category !in pause.categories) return emptyList()
        if (duringCall) riskyAppOpenedDuringCall = true
        if (packageName in continuedPackages) return emptyList()
        if (pauseShownAt[category] != null) {
            // Already asked about this kind of app for this call; asking again is nagging.
            return emptyList()
        }
        pauseShownAt[category] = now
        val minutesSince = lastUnknownCallEndedAt?.let { max(0L, (now - it) / MINUTE).toInt() } ?: 0
        return listOf(
            GuardianAction.ShowPauseCard(category, packageName, appLabel, duringCall, if (duringCall) 0 else minutesSince),
            share(
                EventType.PAUSE_SHOWN,
                if (duringCall) Severity.URGENT else Severity.NOTICE,
                now,
                "app" to appLabel,
                "category" to category.name,
                "duringCall" to duringCall.toString(),
                "minutesSinceCall" to (if (duringCall) 0 else minutesSince).toString(),
            ),
        )
    }

    fun onPauseOutcome(packageName: String, appLabel: String, outcome: PauseOutcome, now: Long): List<GuardianAction> =
        when (outcome) {
            PauseOutcome.CALLED_FAMILY ->
                listOf(share(EventType.PAUSE_CALLED_FAMILY, Severity.NOTICE, now, "app" to appLabel))
            PauseOutcome.CONTINUED -> {
                continuedPackages += packageName
                listOf(share(EventType.PAUSE_CONTINUED, Severity.NOTICE, now, "app" to appLabel))
            }
            PauseOutcome.CLOSED -> emptyList()
        }

    fun onPackageInstalled(packageName: String, appLabel: String, controlCapable: Boolean, now: Long): List<GuardianAction> {
        val p = protection
        if (!p.remoteAccessAlarm.enabled) return emptyList()
        if (packageName in p.remoteAccessAlarm.trustedPackages) return emptyList()
        val armed = isArmed(now)
        return when {
            catalog.isRemoteAccess(packageName, p) -> listOf(
                GuardianAction.ShowRemoteAccessWarning(packageName, appLabel, justInstalled = true, knownRemoteAccessApp = true),
                share(EventType.REMOTE_ACCESS_INSTALLED, Severity.URGENT, now, "app" to appLabel, "afterUnknownCall" to armed.toString()),
            )
            controlCapable && p.remoteAccessAlarm.warnOnControlCapableInstalls -> listOf(
                GuardianAction.ShowRemoteAccessWarning(packageName, appLabel, justInstalled = true, knownRemoteAccessApp = false),
                share(
                    EventType.CONTROL_APP_INSTALLED,
                    if (armed) Severity.URGENT else Severity.NOTICE,
                    now,
                    "app" to appLabel,
                    "afterUnknownCall" to armed.toString(),
                ),
            )
            else -> emptyList()
        }
    }

    private fun longCallActionIfDue(call: CallSession, now: Long): List<GuardianAction> {
        val settings = protection.longCallAlert
        if (!settings.enabled || longCallShared || !call.answered) return emptyList()
        val talk = call.talkMillis(now)
        if (talk < settings.minutes * MINUTE) return emptyList()
        longCallShared = true
        return listOf(
            share(
                EventType.LONG_UNKNOWN_CALL,
                if (riskyAppOpenedDuringCall) Severity.URGENT else Severity.NOTICE,
                now,
                // Deliberately no number, not even masked: Play's Call Log policy forbids
                // deriving call-log data by other means and sending it off the phone.
                "minutes" to (talk / MINUTE).toString(),
                "ongoing" to (call.endedAt == null).toString(),
            ),
        )
    }

    private fun share(type: EventType, severity: Severity, at: Long, vararg data: Pair<String, String>) =
        GuardianAction.Share(GuardianEvent(newId(), type, at, severity, data.toMap()))

    companion object {
        const val MINUTE = 60_000L
        const val SAFE_WORD_MIN_TALK = 30_000L
        const val REMOTE_REWARN = 10 * MINUTE
    }
}
