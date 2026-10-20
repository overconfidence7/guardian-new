package app.guardianmode.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import app.guardianmode.core.EventType
import app.guardianmode.core.Severity
import app.guardianmode.data.LocalState
import app.guardianmode.support.DeviceFixtures
import app.guardianmode.support.DeviceFixtures.DAVID
import app.guardianmode.support.DeviceFixtures.SARAH
import app.guardianmode.support.DeviceFixtures.UNKNOWN
import app.guardianmode.support.DeviceRule
import app.guardianmode.support.DeviceRule.Companion.SAFE_WORD_HEADLINE
import app.guardianmode.support.StubApp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * qa/antigravity/cases/09-long-calls-and-safe-word.md. The long-call threshold is shrunk
 * to 1 minute through the saved config (families can set it), so a real call crosses it
 * in about a minute instead of twenty.
 */
@RunWith(AndroidJUnit4::class)
class CallDeviceTest {
    @get:Rule val rule = DeviceRule()

    private fun LocalState.withLongCallMinutes(minutes: Int) = copy(
        config = config.copy(protection = config.protection.copy(longCallAlert = config.protection.longCallAlert.copy(minutes = minutes))),
    )

    private val safeWordCard = By.text(SAFE_WORD_HEADLINE)

    @Test fun gm_call_001_longCallRecordedOnceWithNoNumber() {
        rule.updateState { it.withLongCallMinutes(1) }
        rule.startAnsweredCall(UNKNOWN)
        Thread.sleep(45_000)
        assertWithMessage("nothing before the threshold").that(rule.events(EventType.LONG_UNKNOWN_CALL)).isEmpty()

        val event = rule.waitForEvent(EventType.LONG_UNKNOWN_CALL, timeoutMs = 40_000)
        assertWithMessage("long-call event by 1m25s").that(event).isNotNull()
        assertThat(event!!.severity).isEqualTo(Severity.NOTICE)
        assertThat(event.data).containsExactly("minutes", "1", "ongoing", "true")
        assertThat(event.data.values.joinToString()).doesNotContain("9998888")

        Thread.sleep(30_000) // still going, well past the threshold
        rule.hangUp(UNKNOWN)
        rule.dismissSafeWordCard()
        assertThat(rule.events(EventType.LONG_UNKNOWN_CALL)).hasSize(1)
    }

    @Test fun gm_call_003_urgentWhenRiskyAppOpenedDuringCall() {
        rule.updateState { it.withLongCallMinutes(1) }
        rule.startAnsweredCall(UNKNOWN)
        rule.open(StubApp.CHASE)
        assertThat(rule.waitFor(rule.pauseCard)).isTrue()
        rule.tap("I'm fine, continue")
        val event = rule.waitForEvent(EventType.LONG_UNKNOWN_CALL, timeoutMs = 90_000)
        assertThat(event).isNotNull()
        assertThat(event!!.severity).isEqualTo(Severity.URGENT)
        rule.hangUp(UNKNOWN)
    }

    @Test fun gm_call_004_familyAndOutgoingLongCallsAreNotRecorded() {
        rule.setState(DeviceFixtures.familyState.withLongCallMinutes(1))
        rule.answeredCall(SARAH, talkMillis = 75_000)
        assertThat(rule.events(EventType.LONG_UNKNOWN_CALL)).isEmpty()

        rule.placeCall(UNKNOWN)
        Thread.sleep(75_000)
        rule.hangUp(UNKNOWN)
        assertThat(rule.events(EventType.LONG_UNKNOWN_CALL)).isEmpty()
        assertThat(rule.state.sharedEvents).isEmpty()
    }

    @Test fun gm_call_010_safeWordCardAfterThirtySecondUnknownCall() {
        rule.setState(DeviceFixtures.familyState)
        rule.answeredCall(UNKNOWN, talkMillis = 32_000)
        assertThat(rule.waitFor(safeWordCard)).isTrue()
        assertThat(rule.seen(By.text("If anyone said they're a grandchild or relative in trouble, ask for your family safe word before doing anything. Your real family will know it."))).isTrue()
        assertWithMessage("word hidden until asked").that(rule.has(By.textContains("bluebird"))).isFalse()
        assertThat(rule.seen(By.text("Call Sarah"))).isTrue()
        assertThat(rule.seen(By.text("Call David"))).isTrue()
        assertThat(rule.seen(By.text("Everything's fine"))).isTrue()

        rule.tap("Show our safe word")
        assertThat(rule.waitText("Your safe word is \"bluebird\"")).isTrue()
        val tapped = System.currentTimeMillis()
        rule.tap("Call David")
        assertThat(rule.waitGone(safeWordCard)).isTrue()
        assertWithMessage("a call to David").that(rule.placedCallTo(DAVID, tapped)).isTrue()
    }

    /** "Exactly 30 s" needs clock control; the 20-s and unanswered halves are covered. */
    @Test fun gm_call_011_shortOrMissedCallsDontShowSafeWord() {
        rule.answeredCall(UNKNOWN, talkMillis = 20_000)
        assertThat(rule.staysAway(safeWordCard)).isTrue()
        rule.ring(DeviceFixtures.UNKNOWN_2)
        Thread.sleep(5_000)
        rule.hangUp(DeviceFixtures.UNKNOWN_2)
        assertThat(rule.staysAway(safeWordCard)).isTrue()
    }

    @Test fun gm_call_013_noWordSetShowsAdviceOnly() {
        rule.answeredCall(UNKNOWN, talkMillis = 32_000)
        assertThat(rule.waitFor(safeWordCard)).isTrue()
        assertThat(rule.has(By.text("Show our safe word"))).isFalse()
        assertThat(rule.has(By.textStartsWith("Call "))).isFalse() // solo: no family to call
        rule.tap("Everything's fine")
        assertThat(rule.waitGone(safeWordCard)).isTrue()
    }

    @Test fun gm_call_014_safeWordSwitchedOffShowsNoCard() {
        val family = DeviceFixtures.familyState
        rule.setState(family.copy(config = family.config.copy(protection = family.config.protection.copy(safeWord = family.config.protection.safeWord.copy(enabled = false)))))
        rule.answeredCall(UNKNOWN, talkMillis = 32_000)
        assertThat(rule.staysAway(safeWordCard, 6_000)).isTrue()
    }

    /** S-16 territory: the card is started while the screen is off; record and check it's there after unlocking. */
    @Test fun gm_call_016_safeWordCardAfterUnlockWhenScreenLockedBeforeHangUp() {
        rule.setState(DeviceFixtures.familyState)
        rule.startAnsweredCall(UNKNOWN)
        Thread.sleep(31_000)
        rule.sleepScreen()
        Thread.sleep(1_000)
        rule.hangUp(UNKNOWN)
        Thread.sleep(3_000)
        rule.wake()
        assertThat(rule.waitFor(safeWordCard)).isTrue()
    }
}
