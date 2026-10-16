package app.guardianmode

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.guardianmode.core.PhoneCallState
import app.guardianmode.ui.MainActivity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end on a real Android build: the scam story from docs/SPEC.md driven through
 * the same entry points Android uses (screening, call state, foreground app).
 */
@RunWith(AndroidJUnit4::class)
class ProtectionFlowTest {
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
        device.pressHome()
    }

    @After fun tearDown() {
        device.pressBack()
    }

    @Test fun homeScreenShowsFamilyTiles() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertThat(device.wait(Until.hasObject(By.desc("Call Sarah")), TIMEOUT)).isTrue()
        assertThat(device.hasObject(By.text("All my apps"))).isTrue()
    }

    @Test fun bankAppAfterUnknownCallShowsPauseCard() {
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
        assertThat(device.wait(Until.hasObject(By.text("Let's pause for a moment.")), TIMEOUT)).isTrue()
        assertThat(device.hasObject(By.text("Call Sarah first"))).isTrue()
        device.findObject(By.text("I'm fine, continue")).click()
        Thread.sleep(500)
        val shared = container.store.current.sharedEvents.map { it.type.name }
        assertThat(shared).containsAtLeast("PAUSE_SHOWN", "PAUSE_CONTINUED")
    }

    @Test fun familyCallNeverArmsTripwires() {
        val brain = container.brain
        brain.screen("+1 555 010 1111", verificationFailed = false, inPhoneContacts = false)
        brain.onCallState(PhoneCallState.RINGING)
        brain.onCallState(PhoneCallState.OFFHOOK)
        brain.onCallState(PhoneCallState.IDLE)
        brain.onForeground("com.chase.sig.android", "Chase")
        assertThat(device.wait(Until.hasObject(By.text("Let's pause for a moment.")), 3_000)).isFalse()
    }

    @Test fun remoteAccessInstallShowsWarning() {
        container.brain.onInstalled("com.anydesk.anydeskandroid", "AnyDesk", controlCapable = true)
        assertThat(device.wait(Until.hasObject(By.textContains("AnyDesk can let someone control your phone")), TIMEOUT)).isTrue()
        device.findObject(By.text("I trust this app")).click()
    }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
