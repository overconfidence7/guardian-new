package app.guardianmode.device

import android.content.Intent
import android.telecom.TelecomManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import app.guardianmode.support.DeviceFixtures
import app.guardianmode.support.DeviceRule
import app.guardianmode.support.StubApp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** qa/antigravity/cases/04-home-screen.md on a device, with Guardian as the real home app. */
@RunWith(AndroidJUnit4::class)
class HomeDeviceTest {
    @get:Rule val rule = DeviceRule()

    private fun chip(label: String) = By.desc("$label. What my family can see.")

    @Test fun gm_home_001_defaultSoloHomeScreen() {
        rule.goHome()
        val now = ZonedDateTime.now()
        val times = listOf(now, now.minusMinutes(1), now.plusMinutes(1))
        val timeShown = times.any { rule.has(By.text(it.format(DateTimeFormatter.ofPattern("h:mm", Locale.getDefault())))) }
        assertWithMessage("time h:mm").that(timeShown).isTrue()
        val dateShown = times.any { rule.has(By.text(it.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())))) }
        assertWithMessage("date").that(dateShown).isTrue()
        val greeting = when (now.hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
        // Solo: no name yet, so the greeting is just the time of day.
        assertWithMessage("greeting").that(rule.has(By.text(greeting))).isTrue()
        assertThat(rule.has(By.text("Welcome to your new home screen."))).isTrue()
        assertThat(rule.waitFor(chip("Protected"))).isTrue()
        for (tile in listOf("Phone", "Messages", "Camera", "Photos", "Reminders", "Help")) {
            assertWithMessage("$tile tile").that(rule.scrollTo(By.desc(tile))).isTrue()
        }
        assertThat(rule.scrollTo(By.text("All my apps"))).isTrue()
    }

    @Test fun gm_home_002_everyDefaultTileOpensTheRightThing() {
        val dialer = rule.context.getSystemService(TelecomManager::class.java).defaultDialerPackage
        rule.goHome()
        rule.scrollAndTap(By.desc("Phone"))
        assertWithMessage("Phone opens the dialer").that(rule.waitForPackage(dialer)).isTrue()

        for (tile in listOf("Messages", "Camera")) {
            rule.goHome()
            rule.scrollAndTap(By.desc(tile))
            val left = rule.waitUntil { rule.topPackage() != rule.pkg }
            Log.i(TAG, "GM-HOME-002 $tile opened ${rule.topPackage()}")
            assertWithMessage("$tile opens another app").that(left).isTrue()
        }

        val pages = mapOf("Photos" to "Family photos", "Reminders" to "Today's reminders", "Help" to "Ask your family for help")
        for ((tile, title) in pages) {
            rule.goHome()
            rule.scrollAndTap(By.desc(tile))
            assertWithMessage("$tile opens \"$title\"").that(rule.waitText(title)).isTrue()
            rule.device.pressBack()
            assertWithMessage("Back from $tile returns home").that(rule.waitFor(rule.shieldChip)).isTrue()
        }
    }

    @Test fun gm_home_006_shieldChipFollowsTheState() {
        rule.goHome()
        assertThat(rule.waitFor(chip("Protected"))).isTrue()

        rule.shell("appops set ${rule.pkg} GET_USAGE_STATS deny")
        rule.goHome()
        assertThat(rule.waitFor(chip("Check me"))).isTrue()

        rule.grantEverything()
        rule.updateState { it.copy(pausedUntil = System.currentTimeMillis() + 60 * 60_000L) }
        rule.goHome()
        assertThat(rule.waitFor(chip("Paused"))).isTrue()

        rule.tapDesc("Paused. What my family can see.")
        assertThat(rule.waitText("What my family can see")).isTrue()
    }

    @Test fun gm_home_009_homeButtonAlwaysLandsOnTheMainScreen() {
        rule.openTransparency()
        rule.goHome()
        assertThat(rule.has(By.text("What my family can see"))).isFalse()

        rule.scrollAndTap(By.text("All my apps").clickable(true))
        assertThat(rule.waitText("Your family hasn't added any apps yet.")).isTrue()
        rule.goHome()
        assertThat(rule.has(By.text("Your family hasn't added any apps yet."))).isFalse()

        rule.open(StubApp.CHASE)
        assertThat(rule.waitForPackage(StubApp.CHASE.packageName)).isTrue()
        rule.goHome()
        assertThat(rule.topPackage()).isEqualTo(rule.pkg)
    }

    @Test fun gm_home_010_allMyApps() {
        rule.goHome()
        rule.scrollAndTap(By.text("All my apps").clickable(true))
        assertThat(rule.waitText("Your family hasn't added any apps yet.")).isTrue()
        assertThat(rule.has(By.text("Phone settings"))).isTrue()

        rule.setState(DeviceFixtures.familyState)
        rule.goHome()
        rule.scrollAndTap(By.text("All my apps").clickable(true))
        assertThat(rule.waitText("Notes")).isTrue()
        assertThat(rule.has(By.text("Phone settings"))).isTrue()
        rule.tap("Notes")
        assertThat(rule.waitForPackage(StubApp.NOTES.packageName)).isTrue()
    }

    @Test fun gm_home_015_gm_rob_010_anotherHomeAppMakesChipSayCheckMe() {
        val other = rule.context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }
            .firstOrNull { it != rule.pkg && it != "com.android.settings" }
        org.junit.Assume.assumeTrue("no other launcher on this image", other != null)
        rule.shell("cmd role add-role-holder android.app.role.HOME $other")
        rule.device.pressHome()
        assertThat(rule.waitForPackage(other!!)).isTrue()

        // Opened from the app drawer, Guardian says something is missing.
        rule.shell("am start -n ${rule.pkg}/app.guardianmode.ui.MainActivity")
        assertThat(rule.waitFor(chip("Check me"))).isTrue()

        rule.shell("cmd role add-role-holder android.app.role.HOME ${rule.pkg}")
        rule.goHome()
        assertThat(rule.waitFor(chip("Protected"))).isTrue()
        assertThat(rule.state.onboardingDone).isTrue() // no re-setup
    }

    private companion object {
        const val TAG = "HomeDeviceTest"
    }
}
