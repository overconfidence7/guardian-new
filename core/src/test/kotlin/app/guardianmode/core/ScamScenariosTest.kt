package app.guardianmode.core

import app.guardianmode.core.PhoneSim.Companion.ANYDESK
import app.guardianmode.core.PhoneSim.Companion.CHASE
import app.guardianmode.core.PhoneSim.Companion.COINBASE
import app.guardianmode.core.PhoneSim.Companion.TARGET
import app.guardianmode.core.PhoneSim.Companion.UNKNOWN
import app.guardianmode.core.PhoneSim.Companion.ZELLE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * qa/antigravity/cases/19-scam-scenarios.md at the rule level: each scenario played as one
 * uninterrupted sequence of events through CallScreeningPolicy, CallTracker and
 * TripwireEngine, with an injected clock. What the parent sees on screen, and Track B
 * delivery to the family, are for the device and backend layers.
 */
class ScamScenariosTest {

    @Test fun gm_scam_001_microsoftSupport() {
        val phone = PhoneSim(PhoneSim.familyConfig())
        // 1. 10:02 an unknown number calls: silenced. The parent answers anyway.
        phone.ring(UNKNOWN)
        assertEquals(ScreeningVerdict.SILENCE, phone.lastDecision?.verdict)
        phone.answer()
        phone.talkMinutes(3)

        // 2. "Install AnyDesk": full-screen warning at once, urgent, during the call.
        val install = phone.install(ANYDESK, "AnyDesk", controlCapable = true)
        assertTrue(install.remoteWarnings().single().justInstalled)
        val installed = install.shared().single()
        assertEquals(EventType.REMOTE_ACCESS_INSTALLED, installed.type)
        assertEquals(Severity.URGENT, installed.severity)
        assertEquals("true", installed.data["afterUnknownCall"])

        // 3. "I trust this app", then "open AnyDesk". Opening is a separate warning from the
        // install one (the 10-minute quiet period only covers repeated opens); recorded as is.
        phone.advanceMinutes(1)
        val opened = phone.open(ANYDESK, "AnyDesk")
        assertEquals(EventType.REMOTE_ACCESS_OPENED, opened.shared().single().type)
        assertEquals(Severity.URGENT, opened.shared().single().severity)
        phone.goHome()
        phone.advanceMinutes(1)
        assertTrue(phone.open(ANYDESK, "AnyDesk").isEmpty(), "no second open warning within 10 minutes")

        // 4. "Open your bank app": pause card during the call ("It's OK to hang up.").
        phone.advanceMinutes(1)
        val bank = phone.open(CHASE, "Chase")
        assertTrue(bank.pauseCards().single().duringCall)
        assertEquals(Severity.URGENT, bank.shared().single().severity)

        // 5. "Tap continue."
        assertEquals(EventType.PAUSE_CONTINUED, phone.pauseOutcome(CHASE, "Chase", PauseOutcome.CONTINUED).shared().single().type)

        // 6. "Now open Zelle": payment is a different category from bank.
        assertEquals(AppCategory.PAYMENT, phone.open(ZELLE, "Zelle").pauseCards().single().category)

        // 7. Past 20 minutes: urgent long-call event, because risky apps were opened.
        val long = phone.talkMinutes(15).shared().single()
        assertEquals(EventType.LONG_UNKNOWN_CALL, long.type)
        assertEquals(Severity.URGENT, long.severity)
        assertEquals("true", long.data["ongoing"])

        // 8. Hang up: the safe-word card.
        assertTrue(phone.hangUp().hasSafeWordCard())

        assertEquals(
            listOf(
                EventType.REMOTE_ACCESS_INSTALLED, EventType.REMOTE_ACCESS_OPENED, EventType.PAUSE_SHOWN,
                EventType.PAUSE_CONTINUED, EventType.PAUSE_SHOWN, EventType.LONG_UNKNOWN_CALL,
            ),
            phone.events.map { it.type },
        )
        // Within the first five minutes the family already has two urgent events.
        val firstFive = phone.events.filter { it.severity == Severity.URGENT && it.type != EventType.LONG_UNKNOWN_CALL }
        assertTrue(firstFive.size >= 2)
    }

    @Test fun gm_scam_002_grandchildInTrouble() {
        val phone = PhoneSim(PhoneSim.familyConfig())
        phone.ring(UNKNOWN)
        phone.answer()
        phone.talkMinutes(1)
        val card = phone.open(TARGET, "Target").pauseCards().single()
        assertEquals(AppCategory.GIFT_CARD, card.category)
        assertTrue(card.duringCall)
        phone.goHome()
        phone.talkMinutes(1)
        assertTrue(phone.hangUp().hasSafeWordCard(), "the safe-word card comes after hang-up")
        // The safe-word card only ever follows a call; nothing shows it during one.
        assertFalse(phone.actions.dropLast(1).hasSafeWordCard())
    }

    @Test fun gm_scam_003_bankFraudDepartmentCallsBackFromAnotherNumber() {
        val phone = PhoneSim()
        // 1. Unknown call, Zelle during it.
        phone.ring(UNKNOWN)
        phone.answer()
        phone.talkMinutes(2)
        assertTrue(phone.open(ZELLE, "Zelle").pauseCards().single().duringCall)
        phone.pauseOutcome(ZELLE, "Zelle", PauseOutcome.CONTINUED)
        phone.goHome()
        phone.hangUp()

        // 2. Calls back 5 minutes later from a different unknown number: everything re-arms.
        phone.advanceMinutes(5)
        phone.ring("5558887777")
        assertTrue(phone.lastDecision!!.unknown)
        phone.answer()
        phone.talkMinutes(2)
        phone.hangUp()
        assertTrue(phone.open(CHASE, "Chase").pauseCards().isNotEmpty())
        phone.goHome()
        assertTrue(phone.open(ZELLE, "Zelle").pauseCards().isNotEmpty(), "\"continue\" on Zelle ended with the new call")

        // 3. Variant: "call us back at 555-999-8888" once the earlier window has passed.
        // S-14: an outgoing call never arms, so no card today.
        phone.goHome()
        phone.advanceMinutes(31)
        phone.dialOut()
        phone.talkMinutes(10)
        phone.hangUp()
        assertTrue(phone.open(CHASE, "Chase").isEmpty())
    }

    @Test fun gm_scam_004_socialSecurityTwentyFiveMinutesOfPressure() {
        val phone = PhoneSim()
        phone.ring(UNKNOWN)
        phone.answer()
        val longCall = phone.talkMinutes(22).shared().single()
        assertEquals(EventType.LONG_UNKNOWN_CALL, longCall.type)
        assertEquals("20", longCall.data["minutes"])
        assertEquals(Severity.NOTICE, longCall.severity, "Coinbase hadn't been opened yet at minute 20")
        val card = phone.open(COINBASE, "Coinbase").pauseCards().single()
        assertEquals(AppCategory.CRYPTO, card.category)
        assertTrue(card.duringCall)
        phone.talkMinutes(3)
        phone.hangUp()
        assertEquals(1, phone.events.count { it.type == EventType.LONG_UNKNOWN_CALL })
    }

    @Test fun gm_scam_005_whatTheScammerCanSwitchOffWithoutTheCode() {
        // Steps 2 and 3 (pause everything / turn off pause cards) are protection switches,
        // step 4 is Stop using, step 5 to 6 go through Settings.
        val needs = LockLevel.entries.associateWith { level ->
            listOf(LockedAction.PROTECTION_SETTINGS, LockedAction.STOP_USING).map { LockPolicy.needsCode(it, level) }
        }
        assertEquals(listOf(false, true), needs[LockLevel.SIMPLE])
        assertEquals(listOf(true, true), needs[LockLevel.GUIDED])
        assertEquals(listOf(true, true), needs[LockLevel.LOCKED])
        for (level in LockLevel.entries) {
            val settingsCovered = LockPolicy.blocks("com.android.settings", LockSettings(level = level), setOf("app.guardianmode"), unlocked = false)
            assertEquals(level == LockLevel.LOCKED, settingsCovered, level.name)
        }

        // Step 3 at Simple: once pause cards are off, the bank app opens with no card and nothing is shared.
        val phone = PhoneSim()
        phone.ring(UNKNOWN)
        phone.answer()
        phone.config = phone.config.copy(protection = phone.config.protection.copy(pauseTripwires = PauseSettings(enabled = false)))
        assertTrue(phone.open(CHASE, "Chase").isEmpty())
        assertTrue(phone.events.isEmpty())
    }

    @Test fun gm_scam_006_screenHelperInstalledDuringTheCall() {
        val phone = PhoneSim()
        phone.ring(UNKNOWN)
        phone.answer()
        phone.talkMinutes(4)
        val actions = phone.install("com.example.screenhelper", "Screen Helper", controlCapable = true)
        assertFalse(actions.remoteWarnings().single().knownRemoteAccessApp, "the milder warning")
        val event = actions.shared().single()
        assertEquals(EventType.CONTROL_APP_INSTALLED, event.type)
        assertEquals(Severity.URGENT, event.severity, "urgent because of the call")
        // Step 2: at Locked, Settings > Accessibility is behind the code.
        assertTrue(LockPolicy.blocks("com.android.settings", LockSettings(level = LockLevel.LOCKED), emptySet(), unlocked = false))
    }

    @Test fun gm_scam_008_callingTwiceToGetThrough() {
        val phone = PhoneSim()
        phone.ring(UNKNOWN)
        assertEquals(ScreeningVerdict.SILENCE, phone.lastDecision?.verdict)
        phone.talk(25_000)
        phone.hangUp()
        phone.advanceMinutes(1)
        phone.ring(UNKNOWN)
        assertEquals(ScreeningVerdict.ALLOW, phone.lastDecision?.verdict, "the second call within 3 minutes rings")
        // It still counts as unknown, so answering it arms the tripwires as usual.
        phone.answer()
        phone.talkMinutes(2)
        phone.hangUp()
        assertTrue(phone.open(CHASE, "Chase").pauseCards().isNotEmpty())
    }
}
