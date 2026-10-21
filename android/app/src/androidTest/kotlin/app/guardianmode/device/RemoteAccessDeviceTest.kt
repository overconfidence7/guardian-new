package app.guardianmode.device

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import app.guardianmode.core.EventType
import app.guardianmode.core.Severity
import app.guardianmode.support.DeviceFixtures
import app.guardianmode.support.DeviceFixtures.SARAH
import app.guardianmode.support.DeviceFixtures.UNKNOWN
import app.guardianmode.support.DeviceRule
import app.guardianmode.support.StubApp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * qa/antigravity/cases/07-remote-control-alarm.md: stand-ins installed for real with
 * `pm install` (CI pushes the APKs to /data/local/tmp/stubs), so Guardian has to notice
 * the install through Android's package-added broadcast and its package sequence.
 */
@RunWith(AndroidJUnit4::class)
class RemoteAccessDeviceTest {
    @get:Rule val rule = DeviceRule()

    private val anyDeskWarning = By.text("AnyDesk can let someone control your phone.")

    /** Installs [app] and returns ms from `pm install` returning until [warning] shows, or null. */
    private fun installAndTime(app: StubApp, warning: androidx.test.uiautomator.BySelector, timeoutMs: Long = 15_000): Long? {
        val out = rule.installStub(app)
        assertWithMessage("pm install ${app.flavor}").that(out).contains("Success")
        val start = System.currentTimeMillis()
        return rule.timeUntil(warning, timeoutMs, start).also { Log.i(TAG, "${app.label} install warning after ${it ?: "never"} ms") }
    }

    private fun trust() {
        rule.tap("I trust this app")
        assertThat(rule.waitGone(By.text("I trust this app"))).isTrue()
    }

    @Test fun gm_racc_001_gm_perf_005_installingAnyDeskWarnsWithinFiveSeconds() {
        val t = installAndTime(StubApp.ANYDESK, anyDeskWarning)
        assertWithMessage("install warning").that(t).isNotNull()
        assertThat(rule.seen(By.text("Real companies like Microsoft, Apple, Amazon or your bank never ask you to install an app like this over the phone."))).isTrue()
        assertThat(rule.seen(By.text("If someone on the phone asked you to install it, it's OK to hang up."))).isTrue()
        assertThat(rule.seen(By.text("Remove AnyDesk"))).isTrue()
        assertThat(rule.seen(By.text("I trust this app"))).isTrue()
        val event = rule.waitForEvent(EventType.REMOTE_ACCESS_INSTALLED)!!
        assertThat(event.severity).isEqualTo(Severity.URGENT)
        assertThat(event.data).containsAtLeast("app", "AnyDesk", "afterUnknownCall", "false")
        // GM-PERF-005 / the case's target: under 5 s with the screen on.
        assertWithMessage("install warning latency (ms)").that(t).isAtMost(5_000L)
    }

    @Test fun gm_racc_002_installDuringUnknownCallThenChaseGetsPauseCard() {
        rule.startAnsweredCall(UNKNOWN)
        assertThat(installAndTime(StubApp.TEAMVIEWER, By.text("TeamViewer QuickSupport can let someone control your phone."))).isNotNull()
        assertThat(rule.waitForEvent(EventType.REMOTE_ACCESS_INSTALLED)!!.data["afterUnknownCall"]).isEqualTo("true")
        trust()
        rule.open(StubApp.CHASE)
        assertThat(rule.waitFor(rule.pauseCard)).isTrue()
        assertThat(rule.seen(By.textContains("It's OK to hang up."))).isTrue()
        rule.hangUp(UNKNOWN)
    }

    @Test fun gm_racc_005_openingAnyDeskWarnsOnceInTenMinutes() {
        assertThat(installAndTime(StubApp.ANYDESK, anyDeskWarning)).isNotNull()
        trust()
        rule.goHome()
        rule.open(StubApp.ANYDESK)
        assertThat(rule.waitFor(anyDeskWarning)).isTrue()
        val opened = rule.waitForEvent(EventType.REMOTE_ACCESS_OPENED)!!
        assertThat(opened.severity).isEqualTo(Severity.NOTICE)
        assertThat(opened.data).containsAtLeast("app", "AnyDesk", "afterUnknownCall", "false")
        trust()
        rule.goHome()
        rule.open(StubApp.ANYDESK)
        assertThat(rule.waitForPackage(StubApp.ANYDESK.packageName)).isTrue()
        assertThat(rule.staysAway(anyDeskWarning)).isTrue()
        assertThat(rule.events(EventType.REMOTE_ACCESS_OPENED)).hasSize(1)
        // The "11 minutes later it warns again" half needs a clock move; left to the host script.
    }

    @Test fun gm_racc_005_openingAnyDeskAfterUnknownCallIsUrgent() {
        rule.installStub(StubApp.ANYDESK)
        assertThat(rule.waitFor(anyDeskWarning, 15_000)).isTrue()
        trust()
        rule.answeredCall(UNKNOWN, talkMillis = 3_000)
        rule.open(StubApp.ANYDESK)
        assertThat(rule.waitFor(anyDeskWarning)).isTrue()
        val opened = rule.waitForEvent(EventType.REMOTE_ACCESS_OPENED)!!
        assertThat(opened.severity).isEqualTo(Severity.URGENT)
        assertThat(opened.data["afterUnknownCall"]).isEqualTo("true")
    }

    /** Android only keeps the uninstall confirmation up for apps that declare REQUEST_DELETE_PACKAGES. */
    @Test fun gm_racc_006_removeAnyDeskOpensAndroidsUninstallConfirmation() {
        assertThat(installAndTime(StubApp.ANYDESK, anyDeskWarning)).isNotNull()
        rule.tap("Remove AnyDesk")
        val installers = setOf("com.android.packageinstaller", "com.google.android.packageinstaller")
        assertThat(rule.waitUntil { rule.topPackage() in installers }).isTrue()
        Log.i(TAG, "GM-RACC-006 uninstall dialog package: ${rule.topPackage()}")
        Thread.sleep(1_000)
        assertWithMessage("the uninstall confirmation stays up").that(rule.topPackage()).isIn(installers)
        assertThat(rule.seen(By.textContains("AnyDesk"))).isTrue()
        rule.tap("OK")
        assertThat(rule.waitUntil(10_000) { !rule.isInstalled(StubApp.ANYDESK) }).isTrue()
        // At Locked the installer is covered (SPEC F13) except for 30 s after this tap;
        // core LockPolicyTest.gm_racc_006_removeOnAWarningLetsOnlyTheUninstallScreenThrough covers that.
    }

    @Test fun gm_racc_007_trustOnlyDismissesAndTrustsNothing() {
        assertThat(installAndTime(StubApp.ANYDESK, anyDeskWarning)).isNotNull()
        trust()
        assertThat(rule.state.config.protection.remoteAccessAlarm.trustedPackages).isEmpty()
    }

    @Test fun gm_racc_008_callSarahDialsSarahAndClosesWarning() {
        rule.setState(DeviceFixtures.familyState)
        assertThat(installAndTime(StubApp.ANYDESK, anyDeskWarning)).isNotNull()
        val tapped = System.currentTimeMillis()
        rule.tap("Call Sarah")
        assertThat(rule.waitGone(anyDeskWarning)).isTrue()
        assertWithMessage("a call to Sarah").that(rule.placedCallTo(SARAH, tapped)).isTrue()
    }

    @Test fun gm_racc_009_appsAlreadyThereNeverAlarmOnInstallButStillWarnOnOpen() {
        // As if AnyDesk was installed before setup: the install watcher hasn't run yet.
        rule.updateState { it.copy(packageSequence = 0, lastInstallCheckAt = 0) }
        rule.installStub(StubApp.ANYDESK)
        assertThat(rule.staysAway(anyDeskWarning, 6_000)).isTrue()
        kotlinx.coroutines.runBlocking { rule.container.protection.checkInstalls() } // "setup finishes"
        assertThat(rule.staysAway(anyDeskWarning, 3_000)).isTrue()
        assertThat(rule.events(EventType.REMOTE_ACCESS_INSTALLED)).isEmpty()

        rule.open(StubApp.ANYDESK)
        assertThat(rule.waitFor(anyDeskWarning)).isTrue()
    }

    /** The stand-in has one versionCode, so only the same-version reinstall half is covered. */
    @Test fun gm_racc_010_reinstallOverExistingDoesNotAlarm() {
        assertThat(installAndTime(StubApp.ANYDESK, anyDeskWarning)).isNotNull()
        trust()
        assertThat(rule.installStub(StubApp.ANYDESK)).contains("Success")
        assertThat(rule.staysAway(anyDeskWarning, 6_000)).isTrue()
        assertThat(rule.events(EventType.REMOTE_ACCESS_INSTALLED)).hasSize(1)
    }

    @Test fun gm_racc_011_reinstallingAfterRemovalAlarmsAgain() {
        assertThat(installAndTime(StubApp.ANYDESK, anyDeskWarning)).isNotNull()
        trust()
        rule.uninstallStub(StubApp.ANYDESK)
        assertThat(installAndTime(StubApp.ANYDESK, anyDeskWarning)).isNotNull()
        assertThat(rule.waitUntil { rule.events(EventType.REMOTE_ACCESS_INSTALLED).size == 2 }).isTrue()
    }

    @Test fun gm_racc_012_appThatCanControlTheScreenGetsMilderWarning() {
        val warning = By.text("Screen Helper asks to control your screen.")
        assertThat(installAndTime(StubApp.SCREEN_HELPER, warning)).isNotNull()
        assertThat(rule.seen(By.text("Remove Screen Helper"))).isTrue()
        val event = rule.waitForEvent(EventType.CONTROL_APP_INSTALLED)!!
        assertThat(event.severity).isEqualTo(Severity.NOTICE)
        assertThat(event.data).containsAtLeast("app", "Screen Helper", "afterUnknownCall", "false")
        trust()
        rule.goHome()
        rule.open(StubApp.SCREEN_HELPER)
        assertThat(rule.waitForPackage(StubApp.SCREEN_HELPER.packageName)).isTrue()
        assertThat(rule.staysAway(warning)).isTrue()
    }

    @Test fun gm_racc_013_ordinaryNewAppDoesNotAlarm() {
        // Notes is pre-installed: remove it, then install it fresh (the rule puts it back regardless).
        rule.uninstallStub(StubApp.NOTES)
        assertThat(rule.installStub(StubApp.NOTES)).contains("Success")
        assertThat(rule.staysAway(By.textContains("control your"), 6_000)).isTrue()
        assertThat(rule.state.sharedEvents).isEmpty()
    }

    @Test fun gm_racc_014_alarmSwitchedOffThenOn() {
        rule.openTransparency()
        rule.scrollAndTap(By.desc("Remote-control app alarm"))
        assertThat(rule.waitUntil { !rule.state.config.protection.remoteAccessAlarm.enabled }).isTrue()
        rule.goHome()

        rule.installStub(StubApp.TEAMVIEWER)
        rule.installStub(StubApp.ANYDESK)
        assertThat(rule.staysAway(By.textContains("control your phone"), 6_000)).isTrue()
        rule.open(StubApp.ANYDESK)
        assertThat(rule.staysAway(anyDeskWarning)).isTrue()
        assertThat(rule.state.sharedEvents).isEmpty()

        rule.openTransparency()
        rule.scrollAndTap(By.desc("Remote-control app alarm"))
        assertThat(rule.waitUntil { rule.state.config.protection.remoteAccessAlarm.enabled }).isTrue()
        // Installs made while it was off don't alarm later...
        assertThat(rule.events(EventType.REMOTE_ACCESS_INSTALLED)).isEmpty()
        // ...but opening AnyDesk now does.
        rule.open(StubApp.ANYDESK)
        assertThat(rule.waitFor(anyDeskWarning)).isTrue()
    }

    @Test fun gm_racc_015_nothingWhilePaused() {
        rule.updateState { it.copy(pausedUntil = System.currentTimeMillis() + 60 * 60_000L) }
        rule.installStub(StubApp.ANYDESK)
        assertThat(rule.staysAway(anyDeskWarning, 6_000)).isTrue()
        rule.open(StubApp.ANYDESK)
        assertThat(rule.staysAway(anyDeskWarning)).isTrue()
        assertThat(rule.state.sharedEvents).isEmpty()
        rule.updateState { it.copy(pausedUntil = 0) }
        rule.goHome()
        // The spec is silent on installs made during a pause; record what happens.
        Log.i(TAG, "GM-RACC-015 after resume: install flagged=${!rule.staysAway(anyDeskWarning, 4_000)}")
    }

    private companion object {
        const val TAG = "RemoteAccessDeviceTest"
    }
}
