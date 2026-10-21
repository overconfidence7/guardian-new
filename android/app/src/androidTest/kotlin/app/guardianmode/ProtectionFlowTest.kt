package app.guardianmode

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.guardianmode.core.PhoneCallState
import app.guardianmode.support.DeviceRule
import app.guardianmode.support.GuardianReset
import app.guardianmode.ui.MainActivity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end on a real Android build: the scam story from docs/SPEC.md driven through
 * the same entry points Android uses (screening, call state, foreground app).
 */
@RunWith(AndroidJUnit4::class)
class ProtectionFlowTest {
    /** Wakes the screen, grants, resets and, on failure, reports what was on screen like every device test. */
    @get:Rule val rule = DeviceRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val container get() = (context.applicationContext as GuardianApp).container

    @Before fun setUp() {
        val pkg = context.packageName
        device.executeShellCommand("appops set $pkg SYSTEM_ALERT_WINDOW allow")
        device.executeShellCommand("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        runBlocking {
            container.store.update {
                Fixtures.state.copy(pairing = null, outbox = emptyList(), sharedEvents = emptyList(), pausedUntil = 0)
            }
        }
        // Forget calls, cards and warnings earlier tests left in the rule engine's memory
        // (the device tests share this process), then start from Guardian itself so a
        // package left in front by an earlier test still counts as newly opened.
        GuardianReset.brain(container)
        container.brain.onForeground(context.packageName, "Guardian Mode")
        device.pressHome()
    }

    @After fun tearDown() {
        device.pressBack()
    }

    @Test fun gm_home_004_homeScreenShowsFamilyTiles() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), TIMEOUT)
        // Tiles sit below the photo card, so they may only be laid out after scrolling.
        val found = scrollTo(By.desc("Call Sarah")) && scrollTo(By.text("All my apps"))
        if (!found) dumpUi()
        assertThat(found).isTrue()
    }

    @Test fun gm_pause_001_bankAppAfterUnknownCallShowsPauseCard() {
        val brain = container.brain
        brain.screen("+15559998888", verificationFailed = false, inPhoneContacts = false)
        brain.onCallState(PhoneCallState.RINGING)
        brain.onCallState(PhoneCallState.OFFHOOK)
        Thread.sleep(1_500)
        brain.onCallState(PhoneCallState.IDLE)
        // The safe-word card follows a real conversation; dismiss it first.
        if (device.wait(Until.hasObject(By.text("Everything's fine")), TIMEOUT)) {
            device.findObject(By.text("Everything's fine")).click()
        }
        brain.onForeground("com.chase.sig.android", "Chase")
        assertThat(device.wait(Until.hasObject(By.text("Hold on a minute.")), TIMEOUT)).isTrue()
        assertThat(scrollTo(By.text("Call Sarah first"))).isTrue()
        // With "Call Sarah first" above it, the button can sit below the fold at large text.
        assertThat(scrollTo(By.text("I'm fine, continue"))).isTrue()
        device.findObject(By.text("I'm fine, continue")).click()
        // The outcome is recorded on the brain's thread and then saved, so wait for it.
        val end = System.currentTimeMillis() + TIMEOUT
        while (System.currentTimeMillis() < end &&
            container.store.current.sharedEvents.none { it.type.name == "PAUSE_CONTINUED" }) {
            Thread.sleep(100)
        }
        val shared = container.store.current.sharedEvents.map { it.type.name }
        assertThat(shared).containsAtLeast("PAUSE_SHOWN", "PAUSE_CONTINUED")
    }

    @Test fun gm_pause_006_familyCallNeverArmsTripwires() {
        val brain = container.brain
        brain.screen("+1 555 010 1111", verificationFailed = false, inPhoneContacts = false)
        brain.onCallState(PhoneCallState.RINGING)
        brain.onCallState(PhoneCallState.OFFHOOK)
        brain.onCallState(PhoneCallState.IDLE)
        brain.onForeground("com.chase.sig.android", "Chase")
        assertThat(device.wait(Until.hasObject(By.text("Hold on a minute.")), 3_000)).isFalse()
    }

    @Test fun gm_racc_001_remoteAccessInstallShowsWarning() {
        container.brain.onInstalled("com.anydesk.anydeskandroid", "AnyDesk", controlCapable = true)
        assertThat(device.wait(Until.hasObject(By.textContains("AnyDesk can let someone control your phone")), TIMEOUT)).isTrue()
        // At the default 1.3x text size the third button sits below the fold on a Pixel 5.
        assertThat(scrollTo(By.text("I trust this app"))).isTrue()
        device.findObject(By.text("I trust this app")).click()
    }

    private fun scrollTo(selector: BySelector): Boolean {
        repeat(6) {
            if (device.wait(Until.hasObject(selector), 1_000)) return true
            device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.7f)
                ?: device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 20)
        }
        return device.hasObject(selector)
    }

    /** Writes the visible UI tree to logcat (tag UiDump) so CI shows what was on screen. */
    private fun dumpUi() {
        val out = java.io.ByteArrayOutputStream()
        device.dumpWindowHierarchy(out)
        out.toString().chunked(3_000).forEach { android.util.Log.i("UiDump", it) }
    }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
