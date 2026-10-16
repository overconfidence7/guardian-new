package app.guardianmode.core

enum class PhoneCallState { IDLE, RINGING, OFFHOOK }

/** One phone call as Guardian understands it. The number never leaves the phone unmasked. */
data class CallSession(
    val number: String?,
    val unknown: Boolean,
    val incoming: Boolean,
    val ringingAt: Long?,
    val answeredAt: Long?,
    val endedAt: Long?,
) {
    val answered: Boolean get() = answeredAt != null
    val active: Boolean get() = endedAt == null

    fun talkMillis(now: Long): Long {
        val start = answeredAt ?: return 0
        return (endedAt ?: now) - start
    }
}

sealed interface CallUpdate {
    val session: CallSession

    data class Started(override val session: CallSession) : CallUpdate
    data class Answered(override val session: CallSession) : CallUpdate
    data class Ended(override val session: CallSession) : CallUpdate
}

/**
 * Correlates two Android signals that each carry half the story:
 *  - CallScreeningService tells us the number and whether it is unknown, but not
 *    whether the call was answered or how long it lasted.
 *  - The telephony call-state callback tells us ringing / off-hook / idle, but not
 *    the number (that would need call-log access, which Guardian does not request).
 *
 * A screening result followed shortly by RINGING is the same call. RINGING with no
 * screening result means Android did not consult us. Android never screens hidden or
 * restricted numbers, and without READ_CONTACTS it also skips the phone's contacts.
 * So when [screenerSeesContacts] is true (READ_CONTACTS granted), an unscreened call
 * can only be a hidden number and is treated as unknown; otherwise it is ambiguous and
 * treated as known to avoid nagging after calls from family. OFFHOOK straight from
 * IDLE is an outgoing call the parent placed, treated as known.
 */
class CallTracker(
    private val correlationWindowMillis: Long = 20_000L,
    private val screenerSeesContacts: () -> Boolean = { false },
) {
    private data class Screened(val number: String?, val unknown: Boolean, val at: Long, val rejected: Boolean)

    private var pendingScreen: Screened? = null
    private var current: CallSession? = null
    private var lastState = PhoneCallState.IDLE

    val activeCall: CallSession? get() = current

    fun onScreened(number: String?, unknown: Boolean, at: Long, rejected: Boolean = false) {
        pendingScreen = Screened(number, unknown, at, rejected)
    }

    fun onCallState(state: PhoneCallState, at: Long): List<CallUpdate> {
        val previous = lastState
        lastState = state
        val updates = mutableListOf<CallUpdate>()
        when (state) {
            PhoneCallState.RINGING -> {
                // Call-waiting: a second call ringing during an active call is ignored for tracking.
                if (current == null) {
                    val screened = pendingScreen?.takeIf { at - it.at in -2_000L..correlationWindowMillis && !it.rejected }
                    pendingScreen = null
                    val session = CallSession(
                        number = screened?.number,
                        unknown = screened?.unknown ?: screenerSeesContacts(),
                        incoming = true,
                        ringingAt = at,
                        answeredAt = null,
                        endedAt = null,
                    )
                    current = session
                    updates += CallUpdate.Started(session)
                }
            }
            PhoneCallState.OFFHOOK -> {
                val existing = current
                if (existing == null) {
                    // Outgoing call placed by the parent.
                    val session = CallSession(null, unknown = false, incoming = false, ringingAt = null, answeredAt = at, endedAt = null)
                    current = session
                    updates += CallUpdate.Started(session)
                    updates += CallUpdate.Answered(session)
                } else if (existing.answeredAt == null && previous == PhoneCallState.RINGING) {
                    val answered = existing.copy(answeredAt = at)
                    current = answered
                    updates += CallUpdate.Answered(answered)
                }
            }
            PhoneCallState.IDLE -> {
                current?.let {
                    val ended = it.copy(endedAt = at)
                    updates += CallUpdate.Ended(ended)
                }
                current = null
            }
        }
        return updates
    }
}
