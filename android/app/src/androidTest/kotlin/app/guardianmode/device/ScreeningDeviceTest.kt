package app.guardianmode.device

import android.Manifest
import android.provider.CallLog
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import app.guardianmode.core.ScreeningMode
import app.guardianmode.support.DeviceFixtures
import app.guardianmode.support.DeviceFixtures.DR_PATEL
import app.guardianmode.support.DeviceFixtures.PAT
import app.guardianmode.support.DeviceFixtures.PHARMACY
import app.guardianmode.support.DeviceFixtures.SARAH
import app.guardianmode.support.DeviceFixtures.UNKNOWN
import app.guardianmode.support.DeviceFixtures.UNKNOWN_2
import app.guardianmode.support.DeviceFixtures.UNKNOWN_3
import app.guardianmode.support.DeviceRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * qa/antigravity/cases/08-call-screening.md. Real incoming calls through the emulator
 * console go through Telecom, which asks Guardian's CallScreeningService. "Silenced" is
 * told from "ringing" by whether a ringtone player starts ([DeviceRule.ringtonePlaying]).
 */
@RunWith(AndroidJUnit4::class)
class ScreeningDeviceTest {
    @get:Rule val rule = DeviceRule()

    /** Rings [number], watches the ringtone, then lets the call go unanswered. */
    private fun rings(number: String): Boolean {
        assertWithMessage("call from $number reaches the phone").that(rule.ring(number)).isTrue()
        val audible = rule.ringsAudibly()
        Log.i(TAG, "call from $number: audible=$audible")
        rule.hangUp(number)
        return audible
    }

    private fun assertSilenced(number: String) = assertWithMessage("$number is silenced").that(rings(number)).isFalse()
    private fun assertRings(number: String) = assertWithMessage("$number rings out loud").that(rings(number)).isTrue()

    @Test fun gm_scrn_001_unknownCallerIsSilencedButStillShownAndLogged() {
        val before = System.currentTimeMillis()
        assertThat(rule.ring(UNKNOWN)).isTrue() // the call still comes in (state RINGING)...
        assertThat(rule.ringsAudibly()).isFalse() // ...without a ringtone
        rule.hangUp(UNKNOWN)
        // It stays in the call log (read with the shell's permission, as adb would).
        val logged = rule.waitUntil(10_000) {
            rule.asShell(Manifest.permission.READ_CALL_LOG) {
                rule.context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER),
                    "${CallLog.Calls.DATE} >= ?", arrayOf((before - 5_000).toString()), null,
                )?.use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.any { it.filter(Char::isDigit).endsWith(UNKNOWN) } } ?: false
            }
        }
        assertThat(logged).isTrue()
        assertThat(rule.ownLogcat()).doesNotContain("Screening failed")
    }

    @Test fun gm_scrn_002_familyRingsInAnyWrittenFormat() {
        rule.setState(DeviceFixtures.familyState)
        assertRings(SARAH)
        val family = DeviceFixtures.familyState
        rule.setState(family.copy(config = family.config.copy(family = family.config.family.map { if (it.id == "sarah") it.copy(phone = "+1 (555) 010-1111") else it })))
        assertRings(SARAH)
    }

    // The "Contacts revoked" half needs `pm revoke`, which kills the process these tests run in.
    @Test fun gm_scrn_003_phoneContactRings() {
        rule.addPhoneContact("Pat", PAT)
        assertRings(PAT)
    }

    @Test fun gm_scrn_004_guardianContactAndAllowlistRing() {
        rule.setState(DeviceFixtures.familyState)
        assertRings(DR_PATEL)
        assertRings(PHARMACY)
    }

    /** The third call "4 minutes later" needs a 4-minute wait; only the first two are covered. */
    @Test fun gm_scrn_005_gm_scam_008_secondCallWithinThreeMinutesRings() {
        assertSilenced(UNKNOWN_2)
        assertRings(UNKNOWN_2)
    }

    @Test fun gm_scrn_006_screeningSwitchOffLetsUnknownRingAndBackOnSilences() {
        rule.openTransparency()
        rule.scrollAndTap(By.desc("Unknown caller screening"))
        assertThat(rule.waitUntil { rule.state.config.protection.callScreening.mode == ScreeningMode.OFF }).isTrue()
        assertThat(rule.has(By.desc("Unknown caller screening"))).isTrue()
        rule.goHome()
        assertRings(UNKNOWN)

        rule.openTransparency()
        rule.scrollAndTap(By.desc("Unknown caller screening"))
        assertThat(rule.waitUntil { rule.state.config.protection.callScreening.mode == ScreeningMode.SILENCE_UNKNOWN }).isTrue()
        rule.goHome()
        assertSilenced(UNKNOWN_3) // a different stranger, so the repeat-caller rule doesn't apply
    }

    @Test fun gm_scrn_008_pauseEverythingLetsUnknownCallsRing() {
        rule.updateState { it.copy(pausedUntil = System.currentTimeMillis() + 60 * 60_000L) }
        assertRings(UNKNOWN)
    }

    @Test fun gm_scrn_009_withoutScreeningRoleCallsRingAndChipSaysCheckMe() {
        rule.shell("cmd role remove-role-holder android.app.role.CALL_SCREENING ${rule.pkg}")
        assertRings(UNKNOWN)
        rule.goHome()
        assertThat(rule.waitFor(By.desc("Check me. What my family can see."))).isTrue()
    }

    @Test fun gm_scrn_010_outgoingCallsAreNeverBlocked() {
        val start = System.currentTimeMillis()
        rule.placeCall(UNKNOWN)
        assertThat(rule.callState()).isEqualTo(2)
        Log.i(TAG, "GM-SCRN-010 outgoing call up after ${System.currentTimeMillis() - start} ms")
        rule.hangUp(UNKNOWN)
    }

    @Test fun gm_scrn_011_screeningAddsNoNoticeableDelay() {
        fun timeToRing(number: String): Long {
            val start = System.currentTimeMillis()
            rule.requireConsole()
            app.guardianmode.support.EmulatorConsole.incomingCall(number)
            assertWithMessage("call from $number rings").that(rule.waitForCallState(1, 10_000)).isTrue()
            val t = System.currentTimeMillis() - start
            rule.hangUp(number)
            return t
        }
        val numbers = (1..5).map { "555999100$it" }
        val withRole = numbers.map(::timeToRing)
        rule.shell("cmd role remove-role-holder android.app.role.CALL_SCREENING ${rule.pkg}")
        val withoutRole = numbers.map { timeToRing(it.replaceRange(7, 8, "2")) }
        val medWith = withRole.sorted()[2]
        val medWithout = withoutRole.sorted()[2]
        Log.i(TAG, "GM-SCRN-011 ms to ringing: with role $withRole (median $medWith), without $withoutRole (median $medWithout)")
        withRole.forEach { assertWithMessage("no screening timeout (5 s)").that(it).isLessThan(5_000L) }
        assertWithMessage("median delay Guardian adds (ms)").that(medWith - medWithout).isLessThan(1_000L)
    }

    private companion object {
        const val TAG = "ScreeningDeviceTest"
    }
}
