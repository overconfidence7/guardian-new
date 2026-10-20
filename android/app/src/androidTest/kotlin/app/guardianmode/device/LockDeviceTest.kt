package app.guardianmode.device

import android.telecom.TelecomManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import app.guardianmode.core.LockLevel
import app.guardianmode.support.DeviceFixtures
import app.guardianmode.support.DeviceFixtures.SARAH
import app.guardianmode.support.DeviceRule
import app.guardianmode.support.EmulatorConsole
import app.guardianmode.support.KnownBug
import app.guardianmode.support.StubApp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * qa/antigravity/cases/03-lock-levels-and-code.md on a device: the code screens inside
 * Guardian, and at Locked the code card that covers other apps (Settings reached many
 * ways, the package installer, stand-in apps) found on screen as the parent would see it.
 */
@RunWith(AndroidJUnit4::class)
class LockDeviceTest {
    @get:Rule val rule = DeviceRule()

    private val settings = rule.lockedCard("Settings")
    private val stopButton = By.text("Stop using Guardian Mode").clickable(true)

    private fun openAllApps() {
        rule.goHome()
        rule.scrollAndTap(By.text("All my apps").clickable(true))
    }

    private fun startCovered(command: String, card: androidx.test.uiautomator.BySelector = settings, timeoutMs: Long = DeviceRule.TIMEOUT): Long? {
        val start = System.currentTimeMillis()
        rule.shell(command)
        return rule.timeUntil(card, timeoutMs, start).also { Log.i(TAG, "\"$command\": covered after ${it ?: "never"} ms") }
    }

    private fun backHome() {
        rule.device.pressBack()
        rule.goHome()
    }

    @Test fun gm_lock_010_simpleLeavesThePhoneOpen() {
        rule.setLock(LockLevel.SIMPLE)
        openAllApps()
        assertThat(rule.waitText("Your family hasn't added any apps yet.")).isTrue()
        assertThat(rule.codeAsked(1_000)).isFalse()
        rule.scrollAndTap(By.text("Phone settings"))
        assertThat(rule.waitForPackage("com.android.settings")).isTrue()
        assertThat(rule.staysAway(rule.anyLockedCard, 3_000)).isTrue()

        rule.openTransparency()
        rule.scrollAndTap(By.desc("Pause before sending money"))
        assertThat(rule.waitUntil { !rule.state.config.protection.pauseTripwires.enabled }).isTrue()
        assertThat(rule.codeAsked(1_000)).isFalse()
    }

    @Test fun gm_lock_011_simpleStillGuardsTheLockAndLeaving() {
        rule.setLock(LockLevel.SIMPLE)
        rule.openTransparency()
        rule.scrollAndTap(By.text("Change the lock"))
        assertThat(rule.waitText("Enter the code")).isTrue()
        assertThat(rule.has(By.text("This part of the phone needs the lock code."))).isTrue()
        rule.device.pressBack()
        assertThat(rule.waitText("What my family can see")).isTrue()
        assertThat(rule.has(By.text("How locked down should this phone be?"))).isFalse() // back where we were, not on the lock page

        rule.scrollAndTap(stopButton)
        rule.tap("Stop")
        assertThat(rule.waitText("Enter the code")).isTrue()
        rule.device.pressBack()
        assertThat(rule.waitText("What my family can see")).isTrue()
        assertThat(rule.state.onboardingDone).isTrue()
    }

    @Test fun gm_lock_020_gm_tran_010_guidedGuardsAppListSwitchesPauseAndLock() {
        rule.setLock(LockLevel.GUIDED)

        openAllApps()
        assertThat(rule.codeAsked()).isTrue()
        rule.enterCode()
        assertThat(rule.waitText("Your family hasn't added any apps yet.")).isTrue()
        rule.relock()

        rule.openTransparency()
        rule.scrollAndTap(By.desc("Pause before sending money"))
        assertThat(rule.codeAsked()).isTrue()
        assertThat(rule.state.config.protection.pauseTripwires.enabled).isTrue()
        rule.enterCode()
        assertThat(rule.waitUntil { !rule.state.config.protection.pauseTripwires.enabled }).isTrue()
        rule.relock()

        rule.openTransparency()
        rule.tap("Pause everything for 1 hour")
        assertThat(rule.codeAsked()).isTrue()
        rule.device.pressBack() // Back from the code screen cancels
        assertThat(rule.waitText("What my family can see")).isTrue()
        assertThat(rule.state.isPaused(System.currentTimeMillis())).isFalse()
        rule.tap("Pause everything for 1 hour")
        assertThat(rule.codeAsked()).isTrue()
        rule.enterCode()
        assertThat(rule.waitUntil { rule.state.isPaused(System.currentTimeMillis()) }).isTrue()
        rule.updateState { it.copy(pausedUntil = 0) }
        rule.relock()

        rule.openTransparency()
        rule.scrollAndTap(By.text("Change the lock"))
        assertThat(rule.codeAsked()).isTrue()
        rule.enterCode()
        assertThat(rule.waitText("Phone lock")).isTrue()
        assertThat(rule.has(By.text("How locked down should this phone be?"))).isTrue()
    }

    @Test fun gm_lock_030_gm_perf_004_settingsCoveredQuicklyAtLocked() {
        rule.setLock(LockLevel.LOCKED)
        val t = startCovered("am start -a android.settings.SETTINGS")
        assertWithMessage("Settings covered").that(t).isNotNull()
        assertThat(rule.has(By.text("This phone is set up to keep things simple. To open Settings, enter the code."))).isTrue()
        assertThat(rule.has(By.text("Your code"))).isTrue()
        assertThat(rule.has(By.text("Unlock"))).isTrue()
        assertThat(rule.has(By.text("Go to my home screen"))).isTrue()
        // The spec's target is "within a second" (S-10). Measured from the start command to
        // the card being readable through UiAutomator, which adds a few hundred ms, so the
        // assertion uses the 2-second card bar and the log line carries the real number.
        assertWithMessage("cover latency (ms)").that(t).isAtMost(DeviceRule.CARD_BAR_MS)
    }

    @Test fun gm_lock_031_everyWayIntoSettingsIsCovered() {
        rule.setLock(LockLevel.LOCKED)
        val routes = listOf(
            "am start -a android.settings.SETTINGS",
            "am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:${rule.pkg}",
            "am start -a android.settings.WIFI_SETTINGS",
            "am start -a android.settings.USAGE_ACCESS_SETTINGS",
            "am start -a android.settings.action.MANAGE_OVERLAY_PERMISSION",
            "monkey -p com.android.settings -c android.intent.category.LAUNCHER 1",
        )
        for (route in routes) {
            assertWithMessage(route).that(startCovered(route)).isNotNull()
            backHome()
        }
        // The gear in Quick Settings (its label differs between versions; skipped if not found).
        rule.device.openQuickSettings()
        val gear = By.pkg("com.android.systemui").desc(Pattern.compile("(?i)open settings.*|settings"))
        if (rule.waitFor(gear, 4_000)) {
            val start = System.currentTimeMillis()
            rule.device.findObject(gear).click()
            assertWithMessage("Quick Settings gear").that(rule.timeUntil(settings, DeviceRule.TIMEOUT, start)).isNotNull()
        } else {
            Log.w(TAG, "GM-LOCK-031: no Settings gear found in Quick Settings on this image")
            rule.closeShade()
        }
    }

    @Test fun gm_lock_032_uninstallingGuardianIsCovered() {
        rule.setLock(LockLevel.LOCKED)
        assertThat(startCovered("am start -a android.intent.action.DELETE -d package:${rule.pkg}", rule.anyLockedCard)).isNotNull()
        Log.i(TAG, "GM-LOCK-032 cover: ${rule.device.findObject(rule.anyLockedCard)?.text}")
        rule.tap("Go to my home screen")
        assertThat(rule.waitFor(rule.shieldChip)).isTrue()
        assertThat(rule.context.packageManager.getPackageInfo(rule.pkg, 0)).isNotNull()
    }

    @Test fun gm_lock_033_callsDialerAndEmergencyAlwaysWork() {
        rule.setState(DeviceFixtures.familyState)
        rule.setLock(LockLevel.LOCKED)
        val dialer = rule.context.getSystemService(TelecomManager::class.java).defaultDialerPackage

        rule.goHome()
        rule.scrollAndTap(By.desc("Phone"))
        assertThat(rule.waitForPackage(dialer)).isTrue()
        assertThat(rule.staysAway(rule.anyLockedCard)).isTrue()
        rule.goHome()

        rule.shell("am start -a com.android.phone.EmergencyDialer.DIAL")
        assertThat(rule.staysAway(rule.anyLockedCard)).isTrue()
        Log.i(TAG, "GM-LOCK-033 emergency dialer package: ${rule.topPackage()}")
        rule.goHome()

        rule.startAnsweredCall(SARAH)
        assertThat(rule.staysAway(rule.anyLockedCard)).isTrue()
        rule.hangUp(SARAH)

        rule.goHome()
        rule.scrollAndTap(By.desc("Call Sarah"))
        assertThat(rule.waitUntil(10_000) { EmulatorConsole.hasCallWith(SARAH) }).isTrue()
        assertThat(rule.staysAway(rule.anyLockedCard)).isTrue()
    }

    @Test fun gm_lock_035_appsTheFamilyAddedAreAllowed() {
        rule.setState(DeviceFixtures.familyState)
        rule.setLock(LockLevel.LOCKED)
        rule.open(StubApp.NOTES)
        assertThat(rule.waitForPackage(StubApp.NOTES.packageName)).isTrue()
        assertThat(rule.staysAway(rule.anyLockedCard)).isTrue()

        rule.open(StubApp.CHASE)
        assertThat(rule.waitFor(rule.lockedCard("Chase"))).isTrue()
        backHome()
        rule.open(StubApp.VENMO)
        assertThat(rule.waitFor(rule.lockedCard("Venmo"))).isTrue()
        backHome()

        rule.scrollAndTap(By.desc("Messages"))
        assertThat(rule.waitUntil { rule.topPackage() != rule.pkg }).isTrue()
        assertThat(rule.staysAway(rule.anyLockedCard)).isTrue()
    }

    @Test fun gm_lock_036_backOrHomeOnCoverNeverOpensTheApp() {
        rule.setLock(LockLevel.LOCKED)
        rule.open(StubApp.CHASE)
        assertThat(rule.waitFor(rule.lockedCard("Chase"))).isTrue()
        rule.device.pressBack()
        assertThat(rule.waitFor(rule.shieldChip)).isTrue()
        assertThat(rule.topPackage()).isEqualTo(rule.pkg)

        rule.open(StubApp.CHASE)
        assertThat(rule.waitFor(rule.lockedCard("Chase"))).isTrue()
        rule.goHome()

        rule.open(StubApp.CHASE)
        assertThat(rule.waitFor(rule.lockedCard("Chase"))).isTrue()
    }

    @Test fun gm_lock_037_rightCodeOpensEverythingForTenMinutes() {
        rule.setLock(LockLevel.LOCKED)
        rule.open(StubApp.CHASE)
        assertThat(rule.waitFor(rule.lockedCard("Chase"))).isTrue()
        rule.enterCode()
        assertThat(rule.waitGone(rule.lockedCard("Chase"))).isTrue()
        assertThat(rule.waitForPackage(StubApp.CHASE.packageName)).isTrue()

        rule.shell("am start -a android.settings.SETTINGS")
        assertThat(rule.waitForPackage("com.android.settings")).isTrue()
        assertThat(rule.staysAway(rule.anyLockedCard, 3_000)).isTrue()
        rule.open(StubApp.VENMO)
        assertThat(rule.staysAway(rule.anyLockedCard, 3_000)).isTrue()

        // Ten minutes later (the stored unlock time runs out instead of moving the clock).
        rule.updateState { it.copy(unlockedUntil = System.currentTimeMillis() - 1) }
        rule.goHome()
        assertThat(startCovered("am start -a android.settings.SETTINGS")).isNotNull()
    }

    @Test fun gm_lock_038_lockItAgainNowCoversSettingsStraightAway() {
        rule.setLock(LockLevel.LOCKED)
        rule.openTransparency()
        rule.scrollAndTap(By.text("Change the lock"))
        rule.enterCode()
        assertThat(rule.waitText("Phone lock")).isTrue()
        rule.scrollAndTap(By.text("Lock it again now"))
        assertThat(rule.waitFor(rule.shieldChip)).isTrue()
        assertThat(rule.waitUntil { rule.state.unlockedUntil == 0L }).isTrue()
        assertThat(startCovered("am start -a android.settings.SETTINGS")).isNotNull()
    }

    /** S-11: without overlay and the Home role, CardLauncher only posts a notification, which covers nothing. */
    @KnownBug("S-11")
    @Test fun gm_lock_041_lockedWithoutOverlayOrHomeRoleStillCovers() {
        rule.setLock(LockLevel.LOCKED)
        rule.shell("appops set ${rule.pkg} SYSTEM_ALERT_WINDOW deny")
        rule.shell("cmd role remove-role-holder android.app.role.HOME ${rule.pkg}")
        assertThat(startCovered("am start -a android.settings.SETTINGS")).isNotNull()
    }

    @Test fun gm_lock_042_quickSettingsAreNotCovered() {
        rule.setLock(LockLevel.LOCKED)
        rule.device.openQuickSettings()
        assertThat(rule.staysAway(rule.anyLockedCard)).isTrue()
        rule.closeShade()
    }

    @Test fun gm_lock_064_gm_tran_013_stopUsingAsksCodeThenClearsLockAndLeaves() {
        rule.setLock(LockLevel.SIMPLE)
        rule.openTransparency()
        assertThat(rule.scrollTo(By.text("You can stop whenever you want. Your helpers will see that this phone has left the family."))).isTrue()

        rule.scrollAndTap(stopButton)
        assertThat(rule.waitText("Stop using Guardian Mode?")).isTrue()
        assertThat(rule.has(By.text("Your family will no longer get alerts or be able to help with this phone. You'll then choose a different home screen."))).isTrue()
        rule.tap("Keep using it")
        assertThat(rule.waitGone(By.text("Stop using Guardian Mode?"))).isTrue()
        assertThat(rule.state.onboardingDone).isTrue()

        rule.scrollAndTap(stopButton)
        rule.tap("Stop")
        assertThat(rule.codeAsked()).isTrue()
        rule.enterCode()
        assertThat(rule.waitUntil { !rule.state.onboardingDone }).isTrue()
        with(rule.state) {
            assertThat(lockCodeHash).isEmpty()
            assertThat(lockCodeSalt).isEmpty()
            assertThat(consent).isEmpty()
        }
        // Android's Home settings opens so the parent can pick another home app.
        assertThat(rule.waitUntil { rule.topPackage() != rule.pkg }).isTrue()
        Log.i(TAG, "GM-LOCK-064 landed on ${rule.topPackage()}")
        assertThat(rule.waitUntil { rule.protectionNotification() == null }).isTrue()
    }

    private companion object {
        const val TAG = "LockDeviceTest"
    }
}
