package app.guardianmode.device

import android.app.Notification
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.guardianmode.protection.Notifications
import app.guardianmode.support.DeviceRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * qa/antigravity/cases/11-service-and-notifications.md: the persistent notification read
 * back from Android (Guardian can list its own notifications) and used from the shade.
 *
 * Not here: GM-SVC-003 (Stop from "Active apps" force-stops the app, ending these tests),
 * GM-SVC-006/007/008 (reboot, reinstall, force stop) and GM-SVC-011 (revoking
 * POST_NOTIFICATIONS kills the process). Those stay with the host-side script.
 */
@RunWith(AndroidJUnit4::class)
class ServiceDeviceTest {
    @get:Rule val rule = DeviceRule()

    private fun title(n: Notification?) = n?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    @Test fun gm_svc_001_persistentNotificationTextButtonsAndWhereItLeads() {
        assertThat(rule.waitUntil { rule.protectionNotification() != null }).isTrue()
        val sbn = rule.protectionNotification()!!
        val n = sbn.notification
        assertThat(title(n)).isEqualTo("Guardian Mode is on")
        assertThat(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).isEqualTo("Tap to see what your family can see.")
        assertThat(n.actions.map { it.title.toString() }).containsExactly("Ask for help", "Pause for 1 hour").inOrder()
        assertThat(n.channelId).isEqualTo(Notifications.CHANNEL_PROTECTION)
        assertThat(sbn.isOngoing).isTrue()
        assertThat(n.smallIcon.resId).isEqualTo(app.guardianmode.R.drawable.ic_notification)

        rule.device.openNotification()
        assertThat(rule.waitText("Guardian Mode is on")).isTrue()
        rule.tap("Guardian Mode is on")
        assertWithMessage("tapping it opens \"What my family can see\"").that(rule.waitText("What my family can see")).isTrue()
    }

    @Test fun gm_svc_004_askForHelpOpensHelp() {
        val action = rule.notificationAction("Ask for help")
        assertThat(action).isNotNull()
        action!!.click()
        assertThat(rule.waitText("Ask your family for help")).isTrue()
    }

    @Test fun gm_svc_005_pauseForOneHourFromTheShadeAndTurnBackOn() {
        val pause = rule.notificationAction("Pause for 1 hour")
        assertThat(pause).isNotNull()
        pause!!.click()
        assertThat(rule.waitUntil { rule.state.isPaused(System.currentTimeMillis()) }).isTrue()
        assertThat(rule.waitUntil { title(rule.protectionNotification()?.notification) == "Guardian Mode is paused" }).isTrue()
        val paused = rule.protectionNotification()!!.notification
        assertThat(paused.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).isEqualTo("Protection is paused for now.")
        assertThat(paused.actions.map { it.title.toString() }).containsExactly("Ask for help", "Turn back on").inOrder()
        rule.closeShade()

        val resume = rule.notificationAction("Turn back on")
        assertThat(resume).isNotNull()
        resume!!.click()
        assertThat(rule.waitUntil { !rule.state.isPaused(System.currentTimeMillis()) }).isTrue()
        assertThat(rule.waitUntil { title(rule.protectionNotification()?.notification) == "Guardian Mode is on" }).isTrue()
    }

    @Test fun gm_svc_012_fourChannelsWithPlainNames() {
        val nm = rule.context.getSystemService(NotificationManager::class.java)
        val expected = mapOf(
            Notifications.CHANNEL_PROTECTION to Triple("Guardian Mode is on", "Shows that Guardian Mode is protecting this phone. Required while protection runs.", NotificationManager.IMPORTANCE_LOW),
            Notifications.CHANNEL_CARDS to Triple("Pause cards", "Full-screen cards that ask you to pause before sending money.", NotificationManager.IMPORTANCE_HIGH),
            Notifications.CHANNEL_REMINDERS to Triple("Reminders", "Medication and appointment reminders your family set up.", NotificationManager.IMPORTANCE_HIGH),
            Notifications.CHANNEL_FAMILY to Triple("From your family", "New photos and messages from your family.", NotificationManager.IMPORTANCE_DEFAULT),
        )
        assertThat(nm.notificationChannels.map { it.id }).containsExactlyElementsIn(expected.keys)
        for ((id, want) in expected) {
            val channel = nm.getNotificationChannel(id)!!
            assertWithMessage("$id name").that(channel.name.toString()).isEqualTo(want.first)
            assertWithMessage("$id description").that(channel.description).isEqualTo(want.second)
            assertWithMessage("$id importance").that(channel.importance).isEqualTo(want.third)
        }
    }
}
