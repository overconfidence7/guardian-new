package app.guardianmode.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** qa/antigravity/cases/08-call-screening.md, the rule parts (docs/SPEC.md F6). */
class CallScreeningPolicyTest {
    private val config = DeviceConfig(
        family = listOf(FamilyContact("f1", "Sarah", phone = "+1 555 010 1111", isPrimaryHelper = true)),
        contacts = listOf(Contact("c1", "Dr. Patel", "555-010-3333")),
        protection = ProtectionSettings(
            callScreening = ScreeningSettings(
                mode = ScreeningMode.SILENCE_UNKNOWN,
                allowlist = listOf(AllowedNumber("Pharmacy", "+1 555 010 4444")),
            ),
        ),
    )
    private val voicemail = config.copy(
        protection = config.protection.copy(callScreening = config.protection.callScreening.copy(mode = ScreeningMode.VOICEMAIL_UNKNOWN)),
    )
    private val m = 60_000L

    @Test fun gm_scrn_001_unknownCallersAreSilencedNotRejected() {
        val d = CallScreeningPolicy().decide("5559998888", 0, config)
        assertEquals(ScreeningVerdict.SILENCE, d.verdict)
        assertTrue(d.unknown)
        assertEquals("unknown", d.reason)
        // The default for a phone with no family config yet is also Silence.
        assertEquals(ScreeningVerdict.SILENCE, CallScreeningPolicy().decide("5559998888", 0, DeviceConfig()).verdict)
    }

    @Test fun gm_scrn_002_familyRingsWhateverTheFormat() {
        val policy = CallScreeningPolicy()
        for (incoming in listOf("5550101111", "+15550101111", "15550101111", "(555) 010-1111", "555.010.1111")) {
            val d = policy.decide(incoming, 0, config)
            assertEquals(ScreeningVerdict.ALLOW, d.verdict, incoming)
            assertFalse(d.unknown, incoming)
        }
        val stored = config.copy(family = listOf(FamilyContact("f1", "Sarah", phone = "+1 (555) 010-1111")))
        assertEquals(ScreeningVerdict.ALLOW, policy.decide("5550101111", 0, stored).verdict)
    }

    @Test fun gm_scrn_003_phoneContactsRing() {
        val d = CallScreeningPolicy().decide("5550107777", 0, config, inPhoneContacts = true)
        assertEquals(ScreeningVerdict.ALLOW, d.verdict)
        assertFalse(d.unknown)
        // Even in voicemail mode.
        assertEquals(ScreeningVerdict.ALLOW, CallScreeningPolicy().decide("5550107777", 0, voicemail, inPhoneContacts = true).verdict)
    }

    @Test fun gm_scrn_004_guardianContactAndAllowlistRing() {
        val policy = CallScreeningPolicy()
        for (n in listOf("5550103333", "5550104444", "+1 555 010 4444")) {
            val d = policy.decide(n, 0, voicemail)
            assertEquals(ScreeningVerdict.ALLOW, d.verdict, n)
            assertFalse(d.unknown, n)
            assertEquals("known", d.reason, n)
        }
    }

    @Test fun gm_scrn_005_secondCallWithinThreeMinutesRings() {
        val policy = CallScreeningPolicy()
        assertEquals(ScreeningVerdict.SILENCE, policy.decide("5559997777", 0, config).verdict)
        val second = policy.decide("+1 555 999 7777", 2 * m, config)
        assertEquals(ScreeningVerdict.ALLOW, second.verdict)
        assertEquals("repeat-caller", second.reason)
        assertTrue(second.unknown, "a repeat caller still counts as unknown for the tripwires")
        // Four minutes after the second call: silenced again.
        assertEquals(ScreeningVerdict.SILENCE, policy.decide("5559997777", 6 * m, config).verdict)
    }

    @Test fun gm_scrn_005_threeQuickCallsFirstSilencedThenRing() {
        val policy = CallScreeningPolicy()
        val verdicts = listOf(0L, 30_000L, 60_000L).map { policy.decide("5559997777", it, config).verdict }
        assertEquals(listOf(ScreeningVerdict.SILENCE, ScreeningVerdict.ALLOW, ScreeningVerdict.ALLOW), verdicts)
    }

    @Test fun gm_scrn_005_repeatRuleIsPerNumberAndCanBeTurnedOff() {
        val policy = CallScreeningPolicy()
        policy.decide("5559997777", 0, config)
        assertEquals(ScreeningVerdict.SILENCE, policy.decide("5559996666", m, config).verdict, "a different number")
        val noRepeat = config.copy(protection = config.protection.copy(callScreening = config.protection.callScreening.copy(allowRepeatCallers = false)))
        val strict = CallScreeningPolicy()
        strict.decide("5559997777", 0, noRepeat)
        assertEquals(ScreeningVerdict.SILENCE, strict.decide("5559997777", m, noRepeat).verdict)
    }

    @Test fun gm_scrn_006_screeningOffRingsButStillReportsUnknown() {
        val off = config.copy(protection = config.protection.copy(callScreening = ScreeningSettings(mode = ScreeningMode.OFF)))
        val d = CallScreeningPolicy().decide("5559998888", 0, off)
        assertEquals(ScreeningVerdict.ALLOW, d.verdict)
        assertTrue(d.unknown, "tripwires still arm after an unknown call with screening off")
        assertEquals("screening-off", d.reason)
        assertFalse(Transparency.items(off).single { it.key == "screening" }.enabled, "transparency item says Off")
        assertTrue(Transparency.items(voicemail).single { it.key == "screening" }.enabled)
    }

    @Test fun gm_scrn_007_voicemailModeSendsUnknownToVoicemailFamilyStillRings() {
        val policy = CallScreeningPolicy()
        val d = policy.decide("5559998888", 0, voicemail)
        assertEquals(ScreeningVerdict.SEND_TO_VOICEMAIL, d.verdict)
        assertTrue(d.unknown)
        assertEquals(ScreeningVerdict.ALLOW, policy.decide("5550101111", 0, voicemail).verdict)
    }

    @Test fun gm_scrn_007_voicemailModeRepeatCallerRingsWithinThreeMinutes() {
        val policy = CallScreeningPolicy()
        assertEquals(ScreeningVerdict.SEND_TO_VOICEMAIL, policy.decide("5559998888", 0, voicemail).verdict)
        assertEquals(ScreeningVerdict.ALLOW, policy.decide("+1 555 999 8888", 2 * m, voicemail).verdict)
        assertEquals(ScreeningVerdict.SEND_TO_VOICEMAIL, policy.decide("5559998888", 10 * m, voicemail).verdict)
    }

    @Test fun gm_scrn_010_outgoingCallsAreNeverScreenedOrArmAnything() {
        // The screening service lets outgoing calls through before consulting the policy;
        // the tracker treats an outgoing call as known, so nothing follows from it.
        val phone = PhoneSim()
        assertTrue(phone.dialOut().isEmpty())
        assertEquals(null, phone.lastDecision)
        assertFalse(phone.tracker.activeCall!!.unknown)
        assertTrue(phone.talkMinutes(25).isEmpty())
        assertTrue(phone.hangUp().isEmpty())
    }

    @Test fun gm_scrn_012_numberIsNeverInTheDecisionOrAnyEvent() {
        val phone = PhoneSim()
        repeat(3) {
            phone.standardCall()
            phone.advanceMinutes(1)
        }
        phone.ring(PhoneSim.UNKNOWN)
        phone.answer()
        phone.talkMinutes(21)
        phone.open(PhoneSim.CHASE, "Chase")
        phone.hangUp()
        assertTrue(phone.events.isNotEmpty())
        val forms = listOf("5559998888", "555-999-8888", "9998888")
        for (event in phone.events) {
            val text = GuardianJson.encodeEvent(event)
            forms.forEach { assertFalse(it in text, "event ${event.type} contains $it: $text") }
        }
        val d = CallScreeningPolicy().decide("5559998888", 0, config)
        forms.forEach { assertFalse(it in d.reason, "reason contains $it") }
    }

    @Test fun gm_scrn_013_hiddenAndUnusualNumbersDoNotCrash() {
        val policy = CallScreeningPolicy()
        for (n in listOf(null, "", "   ", "anonymous")) {
            val d = policy.decide(n, 0, config)
            assertTrue(d.unknown, "'$n'")
            assertEquals("hidden-number", d.reason, "'$n'")
            assertEquals(ScreeningVerdict.SILENCE, d.verdict, "'$n'")
        }
        // Hidden numbers are never treated as repeat callers.
        assertEquals(ScreeningVerdict.SILENCE, policy.decide(null, 1_000, config).verdict)
        // A short code and an international number are unknown and don't match family.
        assertTrue(policy.decide("72345", 0, config).unknown)
        assertTrue(policy.decide("+44 7700 900123", 0, config).unknown)
    }

    @Test fun gm_scrn_014_spoofedCallerIdNeverRingsEvenAsARepeat() {
        val policy = CallScreeningPolicy()
        policy.decide("5559998888", 0, config)
        val d = policy.decide("5559998888", 1_000, config, callerVerificationFailed = true)
        assertEquals(ScreeningVerdict.SEND_TO_VOICEMAIL, d.verdict)
        assertEquals("verification-failed", d.reason)
    }
}
