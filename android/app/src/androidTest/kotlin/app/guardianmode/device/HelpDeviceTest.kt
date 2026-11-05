package app.guardianmode.device

import android.app.NotificationManager
import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import app.guardianmode.core.EventType
import app.guardianmode.data.Pairing
import app.guardianmode.protection.Notifications
import app.guardianmode.support.DeviceFixtures
import app.guardianmode.support.DeviceRule
import app.guardianmode.ui.help.HelpActivity
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/**
 * The "Help me" half of qa/antigravity/cases/12-help-and-reminders.md that needs Android's own
 * screen-capture consent dialog. The phone is linked to a family (a pairing in the saved state;
 * no Firebase is reached, since nothing here sends), which is what offers "Take a picture of my
 * screen". "Don't send" on the preview is gm_help_005 in HelpPhotosRemindersTest (JVM).
 */
@RunWith(AndroidJUnit4::class)
class HelpDeviceTest {
    @get:Rule val rule = DeviceRule()

    private val helpDir get() = File(rule.context.cacheDir, "help")
    private val takePicture = "Take a picture of my screen"

    /** Android's dialog: a Cancel button that isn't Guardian's (System UI shows it on every level). */
    private val androidsCancel = By.text(Pattern.compile("cancel", Pattern.CASE_INSENSITIVE))

    private fun openHelp() {
        rule.context.startActivity(Intent(rule.context, HelpActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertWithMessage("Help opens on \"Ask Sarah for help\"").that(rule.waitText("Ask Sarah for help")).isTrue()
    }

    private fun captureServiceRunning(): Boolean =
        rule.shell("dumpsys activity services ${rule.pkg}/app.guardianmode.ui.help.ScreenCaptureService").contains("ServiceRecord")

    private fun helpNotifications() = rule.context.getSystemService(NotificationManager::class.java)
        .activeNotifications.filter { it.id == Notifications.ID_HELP_READY }

    /** Taps "Take a picture of my screen", checks Android asks, and cancels; returns what the dialog said. */
    private fun askAndCancel(which: String): String {
        rule.tap(takePicture)
        val asked = rule.waitUntil(15_000) { rule.topPackage() != rule.pkg && rule.has(androidsCancel) }
        val words = rule.screenSummary()
        Log.i(TAG, "GM-HELP-004 $which: Android's dialog in ${rule.topPackage()}: ${words.take(600)}")
        assertWithMessage("$which: Android's screen-capture dialog shows (top=${rule.topPackage()}; on screen: ${words.take(400)})")
            .that(asked).isTrue()
        assertWithMessage("$which: the dialog is Android's, not Guardian's").that(rule.topPackage()).isNotEqualTo(rule.pkg)
        rule.tapOn(androidsCancel)
        assertWithMessage("$which: Cancel goes back to Guardian's Help page").that(rule.waitText(takePicture)).isTrue()
        return words
    }

    /** Cancelling changed nothing: no capture running, no picture, no preview, nothing recorded or queued. */
    private fun assertNothingHappened(which: String, eventsBefore: Int, outboxBefore: Int) {
        assertWithMessage("$which: no \"Send this to\" preview").that(rule.staysAway(By.textStartsWith("Send this to"), 3_000)).isTrue()
        assertWithMessage("$which: no screen capture running").that(captureServiceRunning()).isFalse()
        assertWithMessage("$which: no picture in cache/help").that(helpDir.listFiles().orEmpty().map { it.name }).isEmpty()
        assertWithMessage("$which: no \"Your picture is ready\"").that(helpNotifications()).isEmpty()
        assertWithMessage("$which: nothing recorded").that(rule.events(EventType.HELP_REQUESTED)).isEmpty()
        assertWithMessage("$which: shared events unchanged").that(rule.state.sharedEvents).hasSize(eventsBefore)
        assertWithMessage("$which: nothing queued to send").that(rule.state.outbox).hasSize(outboxBefore)
    }

    /**
     * GM-HELP-004: a second Help, later, shows Android's consent dialog again (Guardian never
     * keeps a capture grant), and cancelling it changes nothing and sends nothing.
     */
    @Test fun gm_help_004_androidAsksEveryTimeAndCancelChangesNothing() {
        rule.setState(DeviceFixtures.familyState.copy(pairing = Pairing("fam", "dev", "Sarah")))
        helpDir.deleteRecursively()
        val events = rule.state.sharedEvents.size
        val outbox = rule.state.outbox.size

        openHelp()
        val first = askAndCancel("first Help")
        assertNothingHappened("first Help", events, outbox)
        rule.back()
        assertWithMessage("Back leaves Help").that(rule.waitGone(By.text(takePicture))).isTrue()

        // "Minutes later": long enough that a kept grant would have been used.
        Thread.sleep(LATER_MS)
        openHelp()
        val second = askAndCancel("second Help")
        assertNothingHappened("second Help", events, outbox)
        Log.i(TAG, "GM-HELP-004 same dialog both times: ${first == second}")
    }

    private companion object {
        const val TAG = "HelpDeviceTest"
        const val LATER_MS = 70_000L
    }
}
