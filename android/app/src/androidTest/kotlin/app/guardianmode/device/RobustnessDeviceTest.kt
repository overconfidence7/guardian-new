package app.guardianmode.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import app.guardianmode.core.EventType
import app.guardianmode.core.LockLevel
import app.guardianmode.support.DeviceFixtures
import app.guardianmode.support.DeviceFixtures.SARAH
import app.guardianmode.support.DeviceFixtures.UNKNOWN
import app.guardianmode.support.DeviceFixtures.UNKNOWN_2
import app.guardianmode.support.DeviceRule
import app.guardianmode.support.StubApp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

/** qa/antigravity/cases/16-robustness.md, the parts that run inside the app's own process. */
@RunWith(AndroidJUnit4::class)
class RobustnessDeviceTest {
    @get:Rule val rule = DeviceRule()

    /**
     * Partial: the time zone change (the rule restores the original afterwards). Turning
     * automatic time off and moving the date needs root on the emulator and is left to the
     * host-side script.
     */
    @Test fun gm_rob_006_timeZoneChangeMovesHomeClockAndPauseEndTime() {
        val target = if (TimeZone.getDefault().id == "Asia/Kolkata") "America/Los_Angeles" else "Asia/Kolkata"
        rule.setTimeZone(target)
        val zone = ZoneId.of(target)
        rule.goHome()
        val clockFollows = rule.waitUntil(15_000) { // the home clock refreshes every 10 s
            val now = ZonedDateTime.now(zone)
            listOf(now, now.minusMinutes(1)).any { rule.has(By.text(it.format(DateTimeFormatter.ofPattern("h:mm", Locale.getDefault())))) }
        }
        assertWithMessage("home clock shows $target time").that(clockFollows).isTrue()

        val until = System.currentTimeMillis() + 60 * 60_000L
        rule.updateState { it.copy(pausedUntil = until) }
        rule.openTransparency()
        val shown = listOf(0L, 60_000L).map {
            "Paused until " + java.time.Instant.ofEpochMilli(until - it).atZone(zone).format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
        }
        assertWithMessage("pause end in $target time").that(rule.waitUntil(12_000) { shown.any { s -> rule.seen(By.text(s)) } }).isTrue()
    }

    @Test fun gm_rob_013_rapidTapsDontDoubleAct() {
        rule.setState(DeviceFixtures.familyState)

        // "Call Sarah first", double-tapped.
        rule.answeredCall(UNKNOWN, talkMillis = 3_000)
        rule.open(StubApp.CHASE)
        assertThat(rule.waitFor(rule.pauseCard)).isTrue()
        assertThat(rule.seen(By.text("Call Sarah first"))).isTrue()
        val call = rule.find(By.text("Call Sarah first")).visibleCenter
        val tapped = System.currentTimeMillis()
        repeat(2) { rule.device.click(call.x, call.y) }
        Thread.sleep(3_000)
        assertThat(rule.events(EventType.PAUSE_CALLED_FAMILY)).hasSize(1)
        assertWithMessage("a call to Sarah").that(rule.placedCallTo(SARAH, tapped)).isTrue()
        Thread.sleep(2_000)
        assertWithMessage("calls to Sarah placed").that(rule.dialedCount(SARAH, tapped)).isEqualTo(1)
        Thread.sleep(DeviceRule.SETTLE)

        // "I'm fine, continue", triple-tapped.
        rule.answeredCall(UNKNOWN_2, talkMillis = 3_000)
        rule.open(StubApp.VENMO)
        assertThat(rule.waitFor(rule.pauseCard)).isTrue()
        assertThat(rule.seen(By.text("I'm fine, continue"))).isTrue()
        val cont = rule.find(By.text("I'm fine, continue")).visibleCenter
        repeat(3) { rule.device.click(cont.x, cont.y) }
        Thread.sleep(2_000)
        assertThat(rule.events(EventType.PAUSE_CONTINUED)).hasSize(1)
        assertThat(rule.has(rule.pauseCard)).isFalse()

        // "Unlock", double-tapped.
        rule.setLock(LockLevel.GUIDED)
        rule.goHome()
        rule.scrollAndTap(By.text("All my apps"))
        // Typed with real key presses (setting the text directly doesn't always reach Compose).
        rule.find(By.desc("Lock code")).click()
        rule.shell("input text ${DeviceFixtures.CODE}")
        rule.hideKeyboard()
        assertThat(rule.seen(By.text("Unlock"))).isTrue()
        val unlock = rule.find(By.text("Unlock")).visibleCenter
        repeat(2) { rule.device.click(unlock.x, unlock.y) }
        assertThat(rule.waitText("Your family hasn't added any apps yet.")).isTrue()
        assertThat(rule.state.lockFailures).isEqualTo(0)
    }
}
