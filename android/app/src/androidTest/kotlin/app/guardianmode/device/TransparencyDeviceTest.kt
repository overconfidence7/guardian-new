package app.guardianmode.device

import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import app.guardianmode.core.GuardianJson
import app.guardianmode.data.LocalState
import app.guardianmode.support.DeviceFixtures
import app.guardianmode.support.DeviceRule
import app.guardianmode.support.KnownBug
import app.guardianmode.support.StubApp
import app.guardianmode.ui.cards.CardKind
import app.guardianmode.ui.cards.CardRequest
import app.guardianmode.ui.cards.GuardianCardActivity
import app.guardianmode.ui.MainActivity
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** qa/antigravity/cases/10-transparency-and-pause.md on a device. */
@RunWith(AndroidJUnit4::class)
class TransparencyDeviceTest {
    @get:Rule val rule = DeviceRule()

    private fun savedState(): LocalState = GuardianJson.json.decodeFromString(
        LocalState.serializer(),
        File(rule.context.filesDir, "datastore/guardian_state.json").readText(),
    )

    private fun pausedUntilTexts(until: Long): List<String> = listOf(0L, -60_000L, 60_000L).map {
        "Paused until " + Instant.ofEpochMilli(until + it).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
    }

    @Test fun gm_tran_001_oneTapFromHome() {
        rule.goHome()
        rule.find(rule.shieldChip).click()
        assertThat(rule.waitText("What my family can see")).isTrue()
        assertThat(rule.seen(By.text("Guardian Mode shares a few safety notes with the people you chose. It never shares your messages, what's said on calls, or where you are. You can turn any of it off."))).isTrue()
    }

    @Test fun gm_tran_004_switchesFlipAndAreSaved() {
        val switches = mapOf<String, (LocalState) -> Boolean>(
            "Pause before sending money" to { it.config.protection.pauseTripwires.enabled },
            "Remote-control app alarm" to { it.config.protection.remoteAccessAlarm.enabled },
            "Long calls with unknown numbers" to { it.config.protection.longCallAlert.enabled },
            "Unknown caller screening" to { it.config.protection.callScreening.mode != app.guardianmode.core.ScreeningMode.OFF },
            "Daily \"I'm OK\"" to { it.config.checkIn.enabled },
        )
        rule.openTransparency()
        for ((title, isOn) in switches) {
            for (want in listOf(false, true)) {
                rule.scrollAndTap(By.desc(title))
                assertWithMessage("$title -> $want").that(rule.waitUntil { isOn(rule.state) == want }).isTrue()
                assertWithMessage("$title switch shows $want").that(rule.waitUntil { rule.switchOn(title) == want }).isTrue()
                // Written to disk, so it survives the process restarting.
                assertWithMessage("$title saved as $want").that(rule.waitUntil { isOn(savedState()) == want }).isTrue()
            }
        }
        // "Help me button" and "Reminders" have no switch.
        assertThat(rule.has(By.desc("Help me button"))).isFalse()
    }

    @Test fun gm_tran_008_pauseEverythingForOneHour() {
        rule.openTransparency()
        rule.tap("Pause everything for 1 hour")
        assertThat(rule.waitText("Turn protection back on")).isTrue()
        val until = rule.state.pausedUntil
        assertThat(until - System.currentTimeMillis()).isIn(com.google.common.collect.Range.closed(58 * 60_000L, 60 * 60_000L))
        assertWithMessage("Paused until h:mm a").that(pausedUntilTexts(until).any { rule.seen(By.text(it)) }).isTrue()

        val n = rule.waitUntil { rule.protectionNotification()?.notification?.extras?.getString("android.title") == "Guardian Mode is paused" }
        assertThat(n).isTrue()
        val notification = rule.protectionNotification()!!.notification
        assertThat(notification.extras.getCharSequence("android.text").toString()).isEqualTo("Protection is paused for now.")
        assertThat(notification.actions.map { it.title.toString() }).contains("Turn back on")

        rule.goHome()
        assertThat(rule.waitFor(By.desc("Paused. What my family can see."))).isTrue()

        rule.openTransparency()
        rule.tap("Turn protection back on")
        assertThat(rule.waitUntil { !rule.state.isPaused(System.currentTimeMillis()) }).isTrue()
        assertThat(rule.waitText("Pause everything for 1 hour")).isTrue()
        assertThat(rule.waitUntil { rule.protectionNotification()?.notification?.extras?.getString("android.title") == "Guardian Mode is on" }).isTrue()
    }

    @Test fun gm_tran_009_protectionComesBackWhenThePauseEnds() {
        rule.updateState { it.copy(pausedUntil = System.currentTimeMillis() + 3_000) }
        Thread.sleep(4_000)
        rule.goHome()
        assertThat(rule.waitFor(By.desc("Protected. What my family can see."), 12_000)).isTrue() // the clock refreshes every 10 s
        rule.answeredCall(DeviceFixtures.UNKNOWN, talkMillis = 3_000)
        rule.open(StubApp.CHASE)
        assertThat(rule.waitFor(rule.pauseCard)).isTrue()
    }

    /** S-05: the service rebuilds the notification only when saved state changes, not when the hour runs out. */
    @KnownBug("S-05")
    @Test fun gm_tran_009_notificationStopsSayingPausedWhenThePauseEnds() {
        rule.updateState { it.copy(pausedUntil = System.currentTimeMillis() + 5_000) }
        assertThat(rule.waitUntil { rule.protectionNotification()?.notification?.extras?.getString("android.title") == "Guardian Mode is paused" }).isTrue()
        val back = rule.waitUntil(25_000) { rule.protectionNotification()?.notification?.extras?.getString("android.title") == "Guardian Mode is on" }
        assertWithMessage("notification says \"Guardian Mode is on\" once the pause is over").that(back).isTrue()
    }

    /** S-02 / docs/SECURITY.md §4: FLAG_SECURE on the transparency and safe-word screens. Also GM-PRIV-013. */
    @KnownBug("S-02")
    @Test fun gm_tran_015_gm_priv_013_transparencyAndSafeWordScreensAreSecure() {
        rule.setState(DeviceFixtures.familyState)
        rule.openTransparency()
        val main = rule.resumedActivity()
        assertThat(main).isInstanceOf(MainActivity::class.java)
        assertWithMessage("transparency screen has FLAG_SECURE")
            .that(main!!.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE).isNotEqualTo(0)

        // The safe-word card, shown the way the brain shows it after a call (the trigger is
        // covered in CallDeviceTest; this checks the window it lands in).
        rule.container.cards.show(CardRequest(CardKind.SAFE_WORD))
        assertThat(rule.waitText(DeviceRule.SAFE_WORD_HEADLINE)).isTrue()
        rule.tap("Show our safe word")
        assertThat(rule.waitTextContains("bluebird")).isTrue()
        val card = rule.resumedActivity()
        assertThat(card).isInstanceOf(GuardianCardActivity::class.java)
        assertWithMessage("safe-word card has FLAG_SECURE")
            .that(card!!.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE).isNotEqualTo(0)
    }
}
