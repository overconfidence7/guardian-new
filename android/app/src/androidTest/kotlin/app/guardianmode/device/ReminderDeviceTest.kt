package app.guardianmode.device

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import app.guardianmode.core.EventType
import app.guardianmode.reminders.ReminderReceiver
import app.guardianmode.support.DeviceFixtures
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
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The reminder half of qa/antigravity/cases/12-help-and-reminders.md. Each test puts a
 * reminder one or two minutes out into the saved config; Guardian reschedules its alarm
 * when the config changes (GuardianApp), and the real alarm fires. The "an hour later"
 * missed check is delivered as the alarm Guardian scheduled would deliver it, instead of
 * waiting an hour. Help-me screenshot cases need Track B (pairing) and are not here.
 */
@RunWith(AndroidJUnit4::class)
class ReminderDeviceTest {
    @get:Rule val rule = DeviceRule()

    private val title = By.text("Blood pressure pill")

    /** Schedules the pill reminder at the next whole minute at least 20 s away; returns when it should fire. */
    private fun reminderSoon(notifyFamilyIfMissed: Boolean = true): Long {
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        var at = now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        if (at.toEpochSecond() - now.toEpochSecond() < 20) at = at.plusMinutes(1)
        val time = at.format(DateTimeFormatter.ofPattern("HH:mm"))
        rule.updateState {
            it.copy(
                config = it.config.copy(
                    reminders = listOf(DeviceFixtures.pillReminder(time, notifyFamilyIfMissed)),
                    checkIn = it.config.checkIn.copy(timezone = ZoneId.systemDefault().id),
                ),
            )
        }
        return at.toInstant().toEpochMilli()
    }

    private fun waitForCard(at: Long): Long? {
        val left = at - System.currentTimeMillis()
        val seen = rule.timeUntil(By.text("Remind me in 10 minutes"), left + LATE_ALLOWANCE)
        return seen?.let { System.currentTimeMillis() - at }.also { Log.i(TAG, "reminder card ${it ?: "never"} ms after its time") }
    }

    @Test fun gm_help_020_reminderCardOnTime() {
        val at = reminderSoon()
        assertWithMessage("reminder card").that(waitForCard(at)).isNotNull()
        assertThat(rule.has(title)).isTrue()
        assertThat(rule.has(By.text("One white tablet with water"))).isTrue()
        assertThat(rule.has(By.text("Done"))).isTrue()
        val shown = java.time.Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
        assertThat(rule.has(By.text(shown))).isTrue()
    }

    /** Spec: the parent sees the reminder without unlocking, or at least a notification on the lock screen (S-16). */
    @Test fun gm_help_021_reminderWhileLocked() {
        val at = reminderSoon()
        rule.sleepScreen()
        Thread.sleep((at - System.currentTimeMillis()).coerceAtLeast(0) + 15_000)
        val posted = rule.waitUntil(LATE_ALLOWANCE) {
            rule.context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any {
                it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() == "Blood pressure pill"
            }
        }
        assertWithMessage("reminder notification posted").that(posted).isTrue()
        rule.shell("input keyevent KEYCODE_WAKEUP") // screen on, still locked
        assertWithMessage("visible without unlocking").that(rule.waitFor(title)).isTrue()
        Log.i(TAG, "GM-HELP-021 locked: top=${rule.topPackage()} card=${rule.has(By.text("Remind me in 10 minutes"))}")
    }

    @Test fun gm_help_022_reminderCoversAnotherAppAndBackReturnsToIt() {
        val at = reminderSoon()
        rule.open(StubApp.NOTES)
        assertThat(rule.waitForPackage(StubApp.NOTES.packageName)).isTrue()
        assertThat(waitForCard(at)).isNotNull()
        assertThat(rule.topPackage()).isEqualTo(rule.pkg)
        rule.device.pressBack()
        assertThat(rule.waitForPackage(StubApp.NOTES.packageName)).isTrue()
    }

    @Test fun gm_help_023_doneIsRecordedAndNothingIsMissed() {
        val at = reminderSoon()
        assertThat(waitForCard(at)).isNotNull()
        rule.tap("Done")
        assertThat(rule.waitGone(title)).isTrue()
        assertThat(rule.waitUntil { "r1@$at" in rule.state.remindersDone }).isTrue()

        rule.goHome()
        rule.scrollAndTap(By.desc("Reminders"))
        assertThat(rule.waitText("Today's reminders")).isTrue()
        assertThat(rule.has(By.desc("Done"))).isTrue()

        sendMissedCheck(at)
        Thread.sleep(2_000)
        assertThat(rule.events(EventType.REMINDER_MISSED)).isEmpty()
    }

    /** "It comes back 10 minutes later" isn't waited for; the card closing without "Done" is checked. */
    @Test fun gm_help_024_snoozeClosesTheCardWithoutMarkingDone() {
        val at = reminderSoon()
        assertThat(waitForCard(at)).isNotNull()
        rule.tap("Remind me in 10 minutes")
        assertThat(rule.waitGone(title)).isTrue()
        assertThat(rule.state.remindersDone).isEmpty()
    }

    @Test fun gm_help_025_missedReminderTellsTheFamily() {
        val at = reminderSoon(notifyFamilyIfMissed = true)
        assertThat(waitForCard(at)).isNotNull()
        rule.goHome() // ignore it

        sendMissedCheck(at)
        val missed = rule.waitForEvent(EventType.REMINDER_MISSED)
        assertThat(missed).isNotNull()
        assertThat(missed!!.data["title"]).isEqualTo("Blood pressure pill")
        rule.openTransparency()
        assertThat(rule.scrollTo(By.text("\"Blood pressure pill\" wasn't marked done."))).isTrue()
    }

    /** The check ReminderScheduler.scheduleMissedCheck set for an hour after the reminder, delivered now. */
    private fun sendMissedCheck(at: Long) {
        rule.context.sendBroadcast(
            Intent(rule.context, ReminderReceiver::class.java)
                .setAction(ReminderReceiver.ACTION_CHECK)
                .putExtra(ReminderReceiver.EXTRA_ID, "r1")
                .putExtra(ReminderReceiver.EXTRA_AT, at),
        )
    }

    private companion object {
        const val TAG = "ReminderDeviceTest"
        /** Exact alarms are allowed in the fast setup; allow a minute for a slow emulator. */
        const val LATE_ALLOWANCE = 60_000L
    }
}
