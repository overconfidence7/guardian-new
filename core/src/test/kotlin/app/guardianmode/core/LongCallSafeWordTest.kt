package app.guardianmode.core

import app.guardianmode.core.PhoneSim.Companion.ANYDESK
import app.guardianmode.core.PhoneSim.Companion.CHASE
import app.guardianmode.core.PhoneSim.Companion.SARAH
import app.guardianmode.core.PhoneSim.Companion.UNKNOWN
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** qa/antigravity/cases/09-long-calls-and-safe-word.md, the rule parts (docs/SPEC.md F7, F8). */
class LongCallSafeWordTest {

    private fun longCallEvents(phone: PhoneSim) = phone.events.filter { it.type == EventType.LONG_UNKNOWN_CALL }

    @Test fun gm_call_001_longCallRecordedOnceAtTwentyMinutesWithNoNumber() {
        val phone = PhoneSim()
        phone.ring("+1 (555) 999-8888")
        phone.answer()
        phone.talkMinutes(19)
        assertTrue(longCallEvents(phone).isEmpty(), "nothing at 19 minutes")
        phone.talkMinutes(2)
        val event = longCallEvents(phone).single()
        assertEquals(Severity.NOTICE, event.severity)
        assertEquals(mapOf("minutes" to "20", "ongoing" to "true"), event.data, "only minutes and ongoing, no number")
        phone.talkMinutes(19)
        phone.hangUp()
        assertEquals(1, longCallEvents(phone).size, "still one at 40 minutes and after hang-up")
    }

    @Test fun gm_call_002_longCallThatEndsBetweenChecksIsRecordedOnceAtHangUp() {
        val phone = PhoneSim()
        phone.ring(UNKNOWN)
        phone.answer()
        phone.talkMinutes(19)
        phone.talkMinutes(2, ticks = false)
        val end = phone.hangUp()
        val event = end.shared().single { it.type == EventType.LONG_UNKNOWN_CALL }
        assertEquals("false", event.data["ongoing"])
        assertEquals("21", event.data["minutes"])
        assertEquals(1, longCallEvents(phone).size)
    }

    @Test fun gm_call_003_urgentWhenTheBankAppWasOpenedDuringTheCall() {
        val phone = PhoneSim()
        phone.ring(UNKNOWN)
        phone.answer()
        phone.talkMinutes(3)
        phone.open(CHASE, "Chase")
        phone.pauseOutcome(CHASE, "Chase", PauseOutcome.CONTINUED)
        phone.talkMinutes(18)
        assertEquals(Severity.URGENT, longCallEvents(phone).single().severity)
    }

    @Test fun gm_call_003_urgentWhenAnyDeskWasOpenedDuringTheCall() {
        val phone = PhoneSim()
        phone.ring(UNKNOWN)
        phone.answer()
        phone.open(ANYDESK, "AnyDesk")
        phone.talkMinutes(21)
        assertEquals(Severity.URGENT, longCallEvents(phone).single().severity)
    }

    @Test fun gm_call_003_bankAppOpenedBeforeTheCallDoesNotMakeItUrgent() {
        val phone = PhoneSim()
        phone.open(CHASE, "Chase")
        phone.goHome()
        phone.ring(UNKNOWN)
        phone.answer()
        phone.talkMinutes(21)
        assertEquals(Severity.NOTICE, longCallEvents(phone).single().severity)
    }

    /**
     * Spec F7: "It is urgent if a money or remote-access app was opened during the call."
     * TripwireEngine.onForeground returns early when pause cards are off
     * (`if (!pause.enabled || !armed) return emptyList()`) before it sets
     * riskyAppOpenedDuringCall, so with pause cards switched off a bank app opened during
     * a long unknown call leaves the long-call notice at NOTICE.
     */
    @Test fun gm_call_003_urgentAfterBankAppEvenWithPauseCardsOff() {
        val phone = PhoneSim(DeviceConfig(protection = ProtectionSettings(pauseTripwires = PauseSettings(enabled = false))))
        phone.ring(UNKNOWN)
        phone.answer()
        phone.talkMinutes(3)
        phone.open(CHASE, "Chase")
        phone.talkMinutes(18)
        assertEquals(Severity.URGENT, longCallEvents(phone).single().severity)
    }

    @Test fun gm_call_004_familyAndOutgoingLongCallsAreNotRecorded() {
        val family = PhoneSim(PhoneSim.familyConfig())
        family.ring(SARAH)
        family.answer()
        family.talkMinutes(25)
        family.hangUp()
        assertTrue(family.events.isEmpty())

        val outgoing = PhoneSim()
        outgoing.dialOut()
        outgoing.talkMinutes(25)
        outgoing.hangUp()
        assertTrue(outgoing.events.isEmpty())
    }

    @Test fun gm_call_005_switchedOffRecordsNothingAndTransparencyShowsTheMinutes() {
        val off = DeviceConfig(protection = ProtectionSettings(longCallAlert = LongCallSettings(enabled = false)))
        val phone = PhoneSim(off)
        phone.ring(UNKNOWN)
        phone.answer()
        phone.talkMinutes(25)
        phone.hangUp()
        assertTrue(longCallEvents(phone).isEmpty())
        assertFalse(Transparency.items(off).single { it.key == "longCall" }.enabled)

        val fifteen = DeviceConfig(protection = ProtectionSettings(longCallAlert = LongCallSettings(minutes = 15)))
        assertEquals(
            "That a call with an unknown number went past 15 minutes.",
            Transparency.items(fifteen).single { it.key == "longCall" }.whatIsShared,
        )
        val p15 = PhoneSim(fifteen)
        p15.ring(UNKNOWN)
        p15.answer()
        p15.talkMinutes(16)
        assertEquals("15", longCallEvents(p15).single().data["minutes"])
    }

    @Test fun gm_call_010_safeWordCardAfterAThirtySecondUnknownCall() {
        val phone = PhoneSim(PhoneSim.familyConfig())
        val actions = phone.standardCall()
        assertTrue(actions.hasSafeWordCard())
        // Two family members with numbers for the "Call ___" buttons.
        assertEquals(2, phone.config.family.count { it.phone.isNotBlank() })
    }

    @Test fun gm_call_011_shortOrMissedCallsDoNotShowItButExactlyThirtySecondsDoes() {
        fun cardAfter(talkMillis: Long, answered: Boolean = true): Boolean {
            val phone = PhoneSim()
            phone.ring(UNKNOWN)
            if (answered) phone.answer()
            phone.talk(talkMillis)
            return phone.hangUp().hasSafeWordCard()
        }
        assertFalse(cardAfter(20_000), "20 s")
        assertFalse(cardAfter(29_999), "just under 30 s")
        assertTrue(cardAfter(30_000), "exactly 30 s")
        assertFalse(cardAfter(60_000, answered = false), "unanswered")
    }

    @Test fun gm_call_012_familyCallsDoNotShowIt() {
        val phone = PhoneSim(PhoneSim.familyConfig())
        phone.ring(SARAH)
        phone.answer()
        phone.talkMinutes(2)
        assertFalse(phone.hangUp().hasSafeWordCard())
    }

    /** Current behavior: the card still comes with no word set (the UI then hides "Show our safe word"). */
    @Test fun gm_call_013_cardStillShowsWithNoSafeWordSet() {
        val phone = PhoneSim(DeviceConfig())
        assertEquals("", phone.config.protection.safeWord.word)
        assertTrue(phone.standardCall().hasSafeWordCard())
    }

    @Test fun gm_call_014_safeWordSwitchedOffShowsNoCard() {
        val phone = PhoneSim(PhoneSim.familyConfig().let { it.copy(protection = it.protection.copy(safeWord = SafeWordSettings(enabled = false, word = "bluebird"))) })
        assertFalse(phone.standardCall().hasSafeWordCard())
    }

    @Test fun gm_call_015_theWordNeverLeavesThePhone() {
        val phone = PhoneSim(PhoneSim.familyConfig())
        phone.ring(UNKNOWN)
        phone.answer()
        phone.install(ANYDESK, "AnyDesk")
        phone.open(ANYDESK, "AnyDesk")
        phone.open(CHASE, "Chase")
        phone.pauseOutcome(CHASE, "Chase", PauseOutcome.CONTINUED)
        phone.talkMinutes(21)
        phone.hangUp()
        phone.pauseOutcome(CHASE, "Chase", PauseOutcome.CALLED_FAMILY)
        assertTrue(phone.events.size >= 5)
        for (event in phone.events) {
            assertFalse("bluebird" in GuardianJson.encodeEvent(event).lowercase(), event.type.name)
        }
        // The card action itself carries no word either.
        assertTrue(phone.actions.contains(GuardianAction.ShowSafeWordCard))
    }
}
