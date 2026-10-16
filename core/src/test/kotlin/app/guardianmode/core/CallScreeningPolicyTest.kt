package app.guardianmode.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallScreeningPolicyTest {
    private val config = DeviceConfig(
        family = listOf(FamilyContact("f1", "Sarah", phone = "+1 555 000 1111", isPrimaryHelper = true)),
        contacts = listOf(Contact("c1", "Dr. Patel", "555-000-2222")),
        protection = ProtectionSettings(
            callScreening = ScreeningSettings(
                mode = ScreeningMode.SILENCE_UNKNOWN,
                allowlist = listOf(AllowedNumber("CVS Pharmacy", "(555) 000-3333")),
            ),
        ),
    )

    @Test fun `family contacts and allowlist ring normally`() {
        val policy = CallScreeningPolicy()
        listOf("5550001111", "+15550002222", "555 000 3333").forEach {
            val d = policy.decide(it, 0, config)
            assertEquals(ScreeningVerdict.ALLOW, d.verdict)
            assertFalse(d.unknown)
        }
    }

    @Test fun `phone contacts ring normally`() {
        val d = CallScreeningPolicy().decide("5559998888", 0, config, inPhoneContacts = true)
        assertEquals(ScreeningVerdict.ALLOW, d.verdict)
        assertFalse(d.unknown)
    }

    @Test fun `unknown callers are silenced in silence mode`() {
        val d = CallScreeningPolicy().decide("5559998888", 0, config)
        assertEquals(ScreeningVerdict.SILENCE, d.verdict)
        assertTrue(d.unknown)
    }

    @Test fun `unknown callers go to voicemail in voicemail mode`() {
        val vm = config.copy(protection = config.protection.copy(callScreening = config.protection.callScreening.copy(mode = ScreeningMode.VOICEMAIL_UNKNOWN)))
        assertEquals(ScreeningVerdict.SEND_TO_VOICEMAIL, CallScreeningPolicy().decide("5559998888", 0, vm).verdict)
    }

    @Test fun `repeat caller within three minutes rings through`() {
        val vm = config.copy(protection = config.protection.copy(callScreening = config.protection.callScreening.copy(mode = ScreeningMode.VOICEMAIL_UNKNOWN)))
        val policy = CallScreeningPolicy()
        assertEquals(ScreeningVerdict.SEND_TO_VOICEMAIL, policy.decide("5559998888", 0, vm).verdict)
        val second = policy.decide("+1 555 999 8888", 2 * 60_000, vm)
        assertEquals(ScreeningVerdict.ALLOW, second.verdict)
        assertEquals("repeat-caller", second.reason)
        assertTrue(second.unknown)
        assertEquals(ScreeningVerdict.SEND_TO_VOICEMAIL, policy.decide("5559998888", 10 * 60_000, vm).verdict)
    }

    @Test fun `spoofed caller id never rings even as a repeat`() {
        val policy = CallScreeningPolicy()
        policy.decide("5559998888", 0, config)
        val d = policy.decide("5559998888", 1_000, config, callerVerificationFailed = true)
        assertEquals(ScreeningVerdict.SEND_TO_VOICEMAIL, d.verdict)
    }

    @Test fun `screening off still reports unknown`() {
        val off = config.copy(protection = config.protection.copy(callScreening = ScreeningSettings(mode = ScreeningMode.OFF)))
        val d = CallScreeningPolicy().decide("5559998888", 0, off)
        assertEquals(ScreeningVerdict.ALLOW, d.verdict)
        assertTrue(d.unknown)
    }

    @Test fun `hidden numbers are unknown`() {
        val d = CallScreeningPolicy().decide(null, 0, config)
        assertTrue(d.unknown)
        assertEquals("hidden-number", d.reason)
    }
}
