package app.guardianmode.support

import android.content.Intent
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.guardianmode.GuardianApp
import app.guardianmode.data.LocalState
import app.guardianmode.protection.GuardianService
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Puts the emulator in the "fast setup" state from the runbook (every permission and
 * role granted, Guardian running), resets Guardian's saved state before each test, and
 * dumps the screen to logcat (tag UiDump) when a test fails.
 */
class DeviceRule(
    private val grantAll: Boolean = true,
    private val initialState: () -> LocalState = { DeviceFixtures.soloState },
) : TestWatcher() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device: UiDevice = UiDevice.getInstance(instrumentation)
    val context = instrumentation.targetContext
    val pkg: String = context.packageName
    val container get() = (context.applicationContext as GuardianApp).container
    val state: LocalState get() = container.store.current

    override fun starting(description: Description) {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        if (grantAll) grantEverything()
        setState(initialState())
        container.brain.onForeground(pkg, "Guardian Mode")
        GuardianService.start(context)
        device.pressHome()
        device.waitForIdle()
    }

    override fun failed(e: Throwable, description: Description) = dumpUi("${description.methodName} failed")

    override fun finished(description: Description) {
        // Leave no call or card behind for the next test.
        if (EmulatorConsole.available) runCatching { EmulatorConsole.hangUp(DeviceFixtures.UNKNOWN) }
        repeat(2) { device.pressBack() }
        device.pressHome()
    }

    fun setState(state: LocalState) = runBlocking { container.store.update { state } }
    fun updateState(transform: (LocalState) -> LocalState) = runBlocking { container.store.update(transform) }

    fun shell(command: String): String = device.executeShellCommand(command)

    fun grantEverything() {
        listOf(
            "pm grant $pkg android.permission.READ_PHONE_STATE",
            "pm grant $pkg android.permission.CALL_PHONE",
            "pm grant $pkg android.permission.READ_CONTACTS",
            "pm grant $pkg android.permission.POST_NOTIFICATIONS",
            "appops set $pkg GET_USAGE_STATS allow",
            "appops set $pkg SYSTEM_ALERT_WINDOW allow",
            "appops set $pkg SCHEDULE_EXACT_ALARM allow",
            "appops set $pkg USE_FULL_SCREEN_INTENT allow",
            "cmd role add-role-holder android.app.role.HOME $pkg",
            "cmd role add-role-holder android.app.role.CALL_SCREENING $pkg",
            "dumpsys deviceidle whitelist +$pkg",
        ).forEach { shell(it) }
    }

    fun requireStub(app: StubApp) =
        assumeTrue("${app.label} stand-in isn't installed", shell("pm path ${app.packageName}").isNotBlank())

    fun requireConsole() = assumeTrue("Emulator console not reachable (not an emulator?)", EmulatorConsole.available)

    /** Opens an app the way the parent would, so Guardian has to notice it through Usage access. */
    fun open(app: StubApp) {
        requireStub(app)
        val launch = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: error("No launcher entry for ${app.packageName}")
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    fun install(apkOnDevice: String) = shell("pm install -r $apkOnDevice")
    fun uninstall(app: StubApp) = shell("pm uninstall ${app.packageName}")

    /** A real incoming call through the emulator's telephony, answered and kept up for [talkMillis]. */
    fun answeredCall(number: String, talkMillis: Long, hangUp: Boolean = true) {
        requireConsole()
        EmulatorConsole.incomingCall(number)
        waitForCallState(1) // ringing
        answer(number)
        waitForCallState(2) // off-hook
        Thread.sleep(talkMillis)
        if (hangUp) {
            EmulatorConsole.hangUp(number)
            waitForCallState(0)
        }
    }

    fun answer(number: String) {
        runCatching { EmulatorConsole.accept(number) }
        if (!waitForCallState(2, timeoutMs = 3_000)) shell("input keyevent KEYCODE_CALL")
    }

    /** 0 idle, 1 ringing, 2 off-hook, from the telephony registry. */
    fun callState(): Int =
        Regex("mCallState=(\\d)").find(shell("dumpsys telephony.registry"))?.groupValues?.get(1)?.toInt() ?: -1

    fun waitForCallState(state: Int, timeoutMs: Long = 10_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (callState() == state) return true
            Thread.sleep(200)
        }
        return false
    }

    fun waitText(text: String, timeoutMs: Long = TIMEOUT): Boolean = device.wait(Until.hasObject(By.text(text)), timeoutMs)
    fun waitTextContains(text: String, timeoutMs: Long = TIMEOUT): Boolean = device.wait(Until.hasObject(By.textContains(text)), timeoutMs)
    fun has(text: String): Boolean = device.hasObject(By.text(text))
    fun tap(text: String) = (device.findObject(By.text(text)) ?: error("No \"$text\" on screen")).click()

    /** Milliseconds until [text] appears, or null if it doesn't within the timeout. */
    fun timeUntil(text: String, timeoutMs: Long = TIMEOUT): Long? {
        val start = System.currentTimeMillis()
        return if (waitText(text, timeoutMs)) System.currentTimeMillis() - start else null
    }

    fun topPackage(): String = device.currentPackageName ?: ""

    fun scrollTo(selector: BySelector): Boolean {
        repeat(6) {
            if (device.wait(Until.hasObject(selector), 1_000)) return true
            device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.7f)
                ?: device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 20)
        }
        return device.hasObject(selector)
    }

    /** Dismisses the safe-word card that follows a real conversation with an unknown number. */
    fun dismissSafeWordCard() {
        if (waitText("Everything's fine", 5_000)) tap("Everything's fine")
        device.waitForIdle()
    }

    fun sharedTypes(): List<String> = state.sharedEvents.map { it.type.name }

    fun dumpUi(reason: String) {
        val out = java.io.ByteArrayOutputStream()
        runCatching { device.dumpWindowHierarchy(out) }
        Log.i("UiDump", "---- $reason ----")
        out.toString().chunked(3_000).forEach { Log.i("UiDump", it) }
    }

    companion object {
        const val TIMEOUT = 5_000L
    }
}
