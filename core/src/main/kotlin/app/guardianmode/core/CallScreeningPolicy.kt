package app.guardianmode.core

/** What the Android CallScreeningService should do with one incoming call. */
enum class ScreeningVerdict {
    /** Ring normally. */
    ALLOW,

    /** Ring silently; unanswered calls go to voicemail as usual. */
    SILENCE,

    /** Decline the call so it goes to voicemail, without a missed-call notification. */
    SEND_TO_VOICEMAIL,
}

data class ScreeningDecision(
    val verdict: ScreeningVerdict,
    /** True when the caller is not a contact, family member or allowlisted number. */
    val unknown: Boolean,
    val reason: String,
)

/**
 * Decides how an incoming call is handled. Without READ_CONTACTS Android only asks a
 * screening app about numbers outside the phone's contacts; with it, every call with a
 * visible number is screened, so the Android layer passes [inPhoneContacts]. Guardian
 * also checks its own family list, contacts and the family-curated allowlist
 * (pharmacy, doctor, church office).
 */
class CallScreeningPolicy(
    private val repeatCallerWindowMillis: Long = 3 * 60_000L,
) {
    private val recentUnknown = ArrayDeque<Pair<String, Long>>()

    fun decide(
        rawNumber: String?,
        at: Long,
        config: DeviceConfig,
        callerVerificationFailed: Boolean = false,
        inPhoneContacts: Boolean = false,
    ): ScreeningDecision {
        val settings = config.protection.callScreening
        val key = PhoneNumbers.matchKey(rawNumber)

        if (inPhoneContacts || isKnown(rawNumber, config)) {
            return ScreeningDecision(ScreeningVerdict.ALLOW, unknown = false, reason = "known")
        }

        pruneOlderThan(at - repeatCallerWindowMillis)
        val isRepeat = key.isNotEmpty() && recentUnknown.any { it.first == key }
        if (key.isNotEmpty()) recentUnknown.addLast(key to at)

        val verdict = when {
            settings.mode == ScreeningMode.OFF -> ScreeningVerdict.ALLOW
            // Network says the caller ID is spoofed: never let it ring.
            callerVerificationFailed -> ScreeningVerdict.SEND_TO_VOICEMAIL
            settings.allowRepeatCallers && isRepeat -> ScreeningVerdict.ALLOW
            settings.mode == ScreeningMode.SILENCE_UNKNOWN -> ScreeningVerdict.SILENCE
            else -> ScreeningVerdict.SEND_TO_VOICEMAIL
        }
        val reason = when {
            settings.mode == ScreeningMode.OFF -> "screening-off"
            callerVerificationFailed -> "verification-failed"
            verdict == ScreeningVerdict.ALLOW -> "repeat-caller"
            key.isEmpty() -> "hidden-number"
            else -> "unknown"
        }
        return ScreeningDecision(verdict, unknown = true, reason = reason)
    }

    fun isKnown(rawNumber: String?, config: DeviceConfig): Boolean {
        if (PhoneNumbers.matchKey(rawNumber).isEmpty()) return false
        return config.family.any { PhoneNumbers.sameLine(it.phone, rawNumber) } ||
            config.contacts.any { PhoneNumbers.sameLine(it.phone, rawNumber) } ||
            config.protection.callScreening.allowlist.any { PhoneNumbers.sameLine(it.number, rawNumber) }
    }

    private fun pruneOlderThan(cutoff: Long) {
        while (recentUnknown.isNotEmpty() && recentUnknown.first().second < cutoff) recentUnknown.removeFirst()
    }
}
