package app.guardianmode.device

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import app.guardianmode.core.EventType
import app.guardianmode.core.LockLevel
import app.guardianmode.core.Severity
import app.guardianmode.data.LocalState
import app.guardianmode.support.DeviceFixtures
import app.guardianmode.support.DeviceFixtures.DAVID
import app.guardianmode.support.DeviceFixtures.UNKNOWN
import app.guardianmode.support.DeviceFixtures.UNKNOWN_2
import app.guardianmode.support.DeviceRule
import app.guardianmode.support.DeviceRule.Companion.SAFE_WORD_HEADLINE
import app.guardianmode.support.StubApp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * qa/antigravity/cases/19-scam-scenarios.md, each script run start to finish on one
 * emulator, playing the caller through the emulator console. The long-call threshold is
 * shrunk to 1 minute in the saved config so "past 20 minutes" happens in about a minute.
 * Each step logs a timeline line (tag ScamScenario).
 */
@RunWith(AndroidJUnit4::class)
class ScamScenarioDeviceTest {
    @get:Rule val rule = DeviceRule(initialState = { DeviceFixtures.familyState })

    private fun step(text: String) = Log.i("ScamScenario", text)

    private fun LocalState.withLongCallMinutes(minutes: Int) = copy(
        config = config.copy(protection = config.protection.copy(longCallAlert = config.protection.longCallAlert.copy(minutes = minutes))),
    )

    private fun cardFor(app: StubApp, words: String) {
        rule.open(app)
        assertWithMessage("pause card for ${app.label}").that(rule.waitFor(rule.pauseCard)).isTrue()
        assertThat(rule.seen(By.textContains("never ask you to $words."))).isTrue()
    }

    @Test fun gm_scam_001_microsoftSupport() {
        rule.updateState { it.withLongCallMinutes(1) }
        step("1. unknown number calls")
        assertThat(rule.ring(UNKNOWN)).isTrue()
        assertWithMessage("stranger is silenced").that(rule.ringsAudibly(4_000)).isFalse()
        rule.answer(UNKNOWN)
        assertThat(rule.waitForCallState(2)).isTrue()
        Thread.sleep(DeviceRule.SETTLE)

        step("2. install AnyDesk during the call")
        rule.installStub(StubApp.ANYDESK)
        assertThat(rule.waitFor(By.text("AnyDesk can let someone control your phone."), 15_000)).isTrue()
        step("3. 'I trust this app', then open AnyDesk")
        rule.tap("I trust this app")
        rule.open(StubApp.ANYDESK)
        val warnedAgain = !rule.staysAway(By.text("AnyDesk can let someone control your phone."), 4_000)
        step("   warning on opening AnyDesk right after the install warning: $warnedAgain")
        if (warnedAgain) rule.tap("I trust this app")

        step("4. open the bank app")
        cardFor(StubApp.CHASE, "move money from your bank")
        assertThat(rule.seen(By.textContains("It's OK to hang up."))).isTrue()
        step("5. 'Tap continue, it's fine'")
        rule.tap("I'm fine, continue")
        step("6. open Zelle")
        cardFor(StubApp.ZELLE, "send money")
        rule.tap("I'm fine, continue")

        step("7. the call goes past the long-call threshold")
        val long = rule.waitForEvent(EventType.LONG_UNKNOWN_CALL, timeoutMs = 90_000)
        assertThat(long).isNotNull()
        assertThat(long!!.severity).isEqualTo(Severity.URGENT)

        step("8. hang up")
        rule.hangUp(UNKNOWN)
        assertThat(rule.waitText(SAFE_WORD_HEADLINE)).isTrue()
        step("shared: ${rule.sharedTypes()}")
        assertThat(rule.sharedTypes()).containsAtLeast("REMOTE_ACCESS_INSTALLED", "PAUSE_SHOWN", "PAUSE_CONTINUED", "LONG_UNKNOWN_CALL")
    }

    @Test fun gm_scam_002_grandchildInTrouble() {
        step("1. 'grandson' calls")
        rule.startAnsweredCall(UNKNOWN)
        val answeredAt = System.currentTimeMillis()
        step("2. open Target to buy gift cards")
        cardFor(StubApp.TARGET, "buy gift cards")
        assertThat(rule.seen(By.textContains("It's OK to hang up."))).isTrue()
        rule.tap("Go to my home screen")
        Thread.sleep((32_000 - (System.currentTimeMillis() - answeredAt)).coerceAtLeast(0))
        step("3. hang up")
        rule.hangUp(UNKNOWN)
        assertThat(rule.waitText(SAFE_WORD_HEADLINE)).isTrue()
        rule.tap("Show our safe word")
        assertThat(rule.waitText("Your safe word is \"bluebird\"")).isTrue()
        val tapped = System.currentTimeMillis()
        rule.tap("Call David")
        assertWithMessage("a call to David").that(rule.placedCallTo(DAVID, tapped)).isTrue()
    }

    @Test fun gm_scam_003_bankFraudDepartmentCallsBack() {
        step("1. first call: move money with Zelle")
        rule.startAnsweredCall(UNKNOWN)
        cardFor(StubApp.ZELLE, "send money")
        rule.tap("I'm fine, continue")
        rule.hangUp(UNKNOWN)
        step("2. 'I'll call you right back' from another number")
        rule.answeredCall(UNKNOWN_2, talkMillis = 3_000)
        cardFor(StubApp.CHASE, "move money from your bank")
        rule.tap("I'm fine, continue")
        step("   the new call re-armed Zelle too")
        cardFor(StubApp.ZELLE, "send money")
        // Step 3 (the parent calls the number back) is gm_pause_011 (S-14).
    }

    @Test fun gm_scam_004_gm_call_006_socialSecurityWithPhoneFaceDown() {
        rule.updateState { it.withLongCallMinutes(1) }
        step("1. unknown call, phone face down (screen off)")
        rule.startAnsweredCall(UNKNOWN)
        rule.sleepScreen()
        val long = rule.waitForEvent(EventType.LONG_UNKNOWN_CALL, timeoutMs = 100_000)
        assertWithMessage("long-call event with the screen off").that(long).isNotNull()
        assertThat(long!!.data).containsEntry("minutes", "1")
        step("2. screen on, open Coinbase")
        rule.wake()
        cardFor(StubApp.COINBASE, "send crypto or Bitcoin")
        assertThat(rule.seen(By.textContains("It's OK to hang up."))).isTrue()
        rule.hangUp(UNKNOWN)
    }

    /**
     * Every in-app route to switching Guardian off asks for the code at Guided and Locked,
     * during a live unknown call; at Locked, Settings pages that could switch it off are
     * covered. The notification route (step 1) is gm_pause_032 (S-03).
     */
    @Test fun gm_scam_005_scammerCantSwitchGuardianOffWithoutTheCode() {
        rule.startAnsweredCall(UNKNOWN)
        for (level in listOf(LockLevel.GUIDED, LockLevel.LOCKED)) {
            rule.setLock(level)
            step("$level 2. shield > Pause everything")
            rule.openTransparency()
            rule.tap("Pause everything for 1 hour")
            assertWithMessage("$level pause asks for the code").that(rule.codeAsked()).isTrue()
            rule.back()

            step("$level 3. shield > Pause before sending money off")
            rule.openTransparency()
            rule.scrollAndTap(By.desc("Pause before sending money"))
            assertWithMessage("$level switch asks for the code").that(rule.codeAsked()).isTrue()
            rule.back()

            step("$level 4. shield > Stop using Guardian Mode")
            rule.openTransparency()
            rule.scrollAndTap(By.text("Stop using Guardian Mode"))
            rule.tap("Stop")
            assertWithMessage("$level stop asks for the code").that(rule.codeAsked()).isTrue()
            rule.back()

            assertThat(rule.state.isPaused(System.currentTimeMillis())).isFalse()
            assertThat(rule.state.config.protection.pauseTripwires.enabled).isTrue()
            assertThat(rule.state.onboardingDone).isTrue()

            if (level == LockLevel.LOCKED) {
                for (route in listOf(
                    "am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:${rule.pkg}",
                    "am start -a android.settings.USAGE_ACCESS_SETTINGS",
                    "am start -a android.settings.action.MANAGE_OVERLAY_PERMISSION",
                )) {
                    step("$level 5/6. $route")
                    rule.shell(route)
                    assertWithMessage("$route covered").that(rule.waitFor(rule.lockedCard("Settings"))).isTrue()
                    rule.back()
                }
            }
            rule.goHome()
        }
        assertThat(rule.callState()).isEqualTo(2)
        rule.hangUp(UNKNOWN)
    }

    @Test fun gm_scam_006_appNotOnTheListDuringCall() {
        rule.startAnsweredCall(UNKNOWN)
        rule.installStub(StubApp.SCREEN_HELPER)
        assertThat(rule.waitFor(By.text("Screen Helper asks to control your screen."), 15_000)).isTrue()
        val event = rule.waitForEvent(EventType.CONTROL_APP_INSTALLED)!!
        assertThat(event.severity).isEqualTo(Severity.URGENT)
        assertThat(event.data["afterUnknownCall"]).isEqualTo("true")
        rule.hangUp(UNKNOWN)
    }
}
