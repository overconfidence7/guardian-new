package app.guardianmode.support

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.media.AudioManager
import android.os.Process
import android.provider.ContactsContract
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import app.guardianmode.GuardianApp
import app.guardianmode.core.EventType
import app.guardianmode.core.GuardianEvent
import app.guardianmode.core.LockLevel
import app.guardianmode.data.LocalState
import app.guardianmode.protection.GuardianService
import app.guardianmode.protection.Notifications
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.util.TimeZone
import java.util.regex.Pattern

/**
 * Puts the emulator in the "fast setup" state from the runbook (every permission and
 * role granted, Guardian running), resets Guardian's saved state and in-memory rule state
 * before each test, and dumps the screen to logcat (tag UiDump) when a test fails.
 *
 * Afterwards it hangs up every call, uninstalls stand-ins the test installed, puts back
 * stand-ins it removed, deletes phone contacts it added, restores the time zone and
 * every permission, and presses Home, so tests stay independent.
 *
 * Runtime permissions are never revoked here: `pm revoke` kills the app's process, and
 * the tests run inside it. Cases that need a revoked runtime permission (Contacts,
 * notifications) are left to the host-side script.
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

    private val installed = linkedSetOf<StubApp>()
    private val removed = linkedSetOf<StubApp>()
    private val contactIds = mutableListOf<Long>()
    private var originalTimeZone: String? = null

    override fun starting(description: Description) {
        wake()
        // Long waits (a 1-minute "long call") must not let the screen time out underneath.
        shell("svc power stayon true")
        shell("settings put system screen_off_timeout 1800000")
        if (grantAll) grantEverything()
        runCatching {
            val audio = context.getSystemService(AudioManager::class.java)
            audio.setStreamVolume(AudioManager.STREAM_RING, (audio.getStreamMaxVolume(AudioManager.STREAM_RING) * 5) / 7, 0)
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.activeNotifications.filter { it.id != Notifications.ID_PROTECTION }.forEach { nm.cancel(it.id) }
        resetState(initialState())
        GuardianReset.brain(container)
        GuardianService.start(context)
        device.pressHome()
        device.waitForIdle()
    }

    override fun failed(e: Throwable, description: Description) = dumpUi("${description.methodName} failed")

    override fun finished(description: Description) {
        runCatching { hangUpEverything() }
        installed.forEach { runCatching { shell("pm uninstall ${it.packageName}") } }
        removed.forEach { runCatching { shell("pm install -r ${it.apkOnDevice}") } }
        runCatching { deleteContacts() }
        originalTimeZone?.let { runCatching { setTimeZone(it) } }
        if (grantAll) runCatching { grantEverything() }
        wake()
        repeat(2) { device.pressBack() }
        device.pressHome()
    }

    // ---- State ----

    /** Replaces the saved state, keeping the install watcher's bookmark so new installs still alarm. */
    fun setState(state: LocalState) = runBlocking {
        container.store.update { state.copy(packageSequence = it.packageSequence, lastInstallCheckAt = it.lastInstallCheckAt) }
    }

    fun updateState(transform: (LocalState) -> LocalState) = runBlocking { container.store.update(transform) }

    /** A fresh state, then the install watcher's first run, so apps already on the phone never alarm. */
    fun resetState(state: LocalState) = runBlocking {
        container.store.update { state }
        container.protection.checkInstalls()
    }

    /** Sets the parent's lock code and level, locked (as after "Lock it again now"). */
    fun setLock(level: LockLevel, code: String = DeviceFixtures.CODE) = runBlocking {
        container.lock.setCode(code)
        container.store.update { it.copy(config = it.config.copy(lock = it.config.lock.copy(level = level)), unlockedUntil = 0) }
    }

    fun events(type: EventType): List<GuardianEvent> = state.sharedEvents.filter { it.type == type }

    fun sharedTypes(): List<String> = state.sharedEvents.map { it.type.name }

    /** Waits for the brain (which works on its own thread) to record an event of [type]. */
    fun waitForEvent(type: EventType, timeoutMs: Long = TIMEOUT): GuardianEvent? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            events(type).firstOrNull()?.let { return it }
            Thread.sleep(100)
        }
        return events(type).firstOrNull()
    }

    fun waitUntil(timeoutMs: Long = TIMEOUT, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    // ---- Device ----

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

    fun wake() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    fun sleepScreen() = shell("input keyevent KEYCODE_SLEEP")

    fun isScreenOn(): Boolean = context.getSystemService(android.os.PowerManager::class.java).isInteractive

    /** Runs [block] with the shell's permissions (contacts, call log, time zone), as adb would. */
    fun <T> asShell(vararg permissions: String, block: () -> T): T {
        instrumentation.uiAutomation.adoptShellPermissionIdentity(*permissions)
        try {
            return block()
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    fun setTimeZone(id: String) {
        if (originalTimeZone == null) originalTimeZone = TimeZone.getDefault().id
        asShell(Manifest.permission.SET_TIME_ZONE) { context.getSystemService(AlarmManager::class.java).setTimeZone(id) }
    }

    /** Saves a contact in the phone's own Contacts app, the way the parent would. */
    fun addPhoneContact(name: String, number: String) = asShell(Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS) {
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build(),
        )
        val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        contactIds += ContentUris.parseId(results[0].uri!!)
    }

    private fun deleteContacts() {
        if (contactIds.isEmpty()) return
        asShell(Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS) {
            val uri = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
            contactIds.forEach { id -> context.contentResolver.delete(ContentUris.withAppendedId(uri, id), null, null) }
        }
        contactIds.clear()
    }

    // ---- Stand-in apps ----

    fun isInstalled(app: StubApp): Boolean = shell("pm path ${app.packageName}").isNotBlank()

    fun requireStub(app: StubApp) = assumeTrue("${app.label} stand-in isn't installed", isInstalled(app))

    fun requireStubApk(app: StubApp) =
        assumeTrue("${app.apkOnDevice} wasn't pushed", shell("ls ${app.apkOnDevice}").contains(app.flavor))

    /**
     * Opens an app the way the parent would (a launcher start from outside Guardian), so
     * Guardian has to notice it through Usage access. Returns when the start was sent.
     */
    fun open(app: StubApp) {
        requireStub(app)
        lastOpenAt = System.currentTimeMillis()
        shell("am start -n ${app.component} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER")
    }

    /** When the last [open] sent its start command; latencies are measured from here. */
    var lastOpenAt: Long = 0
        private set

    /** `pm install -r` of a pushed stand-in; one that wasn't there before is uninstalled after the test. */
    fun installStub(app: StubApp): String {
        requireStubApk(app)
        val wasThere = isInstalled(app)
        val out = shell("pm install -r ${app.apkOnDevice}")
        if (!wasThere) {
            if (app.preinstalled) removed.remove(app) else installed += app
        }
        return out
    }

    fun uninstallStub(app: StubApp): String {
        val out = shell("pm uninstall ${app.packageName}")
        if (app.preinstalled) removed += app else installed.remove(app)
        return out
    }

    // ---- Calls ----

    fun requireConsole() = assumeTrue("Emulator console not reachable (not an emulator?)", EmulatorConsole.available)

    /** A real incoming call from [number]; returns once it rings (state 1). */
    fun ring(number: String): Boolean {
        requireConsole()
        EmulatorConsole.incomingCall(number)
        return waitForCallState(1)
    }

    /** Rings and answers, leaving the call up. */
    fun startAnsweredCall(number: String) {
        check(ring(number)) { "Call from $number never rang" }
        answer(number)
        check(waitForCallState(2)) { "Call from $number wasn't answered" }
        Thread.sleep(SETTLE) // let PhoneStateReceiver hand OFFHOOK to the brain
    }

    /** A real incoming call through the emulator's telephony, answered and kept up for [talkMillis]. */
    fun answeredCall(number: String, talkMillis: Long, hangUp: Boolean = true) {
        startAnsweredCall(number)
        Thread.sleep(talkMillis)
        if (hangUp) hangUp(number)
    }

    fun answer(number: String) {
        runCatching { EmulatorConsole.accept(number) }
        if (!waitForCallState(2, timeoutMs = 3_000)) shell("input keyevent KEYCODE_CALL")
    }

    fun hangUp(number: String) {
        runCatching { EmulatorConsole.hangUp(number) }
        if (!waitForCallState(0)) hangUpEverything()
        Thread.sleep(SETTLE) // let PhoneStateReceiver hand IDLE to the brain
    }

    /** The parent places a call (as `am start -a android.intent.action.CALL` from adb). */
    fun placeCall(number: String) {
        requireConsole()
        shell("am start -a android.intent.action.CALL -d tel:$number")
        waitUntil(10_000) { EmulatorConsole.hasCallWith(number) }
        // The emulator's modem may leave an outgoing call "alerting" until accepted.
        Thread.sleep(1_000)
        runCatching { EmulatorConsole.activeNumbers().filter { it.filter(Char::isDigit).endsWith(number) }.forEach(EmulatorConsole::accept) }
        waitForCallState(2)
    }

    fun hangUpEverything() {
        if (EmulatorConsole.available) runCatching { EmulatorConsole.hangUpAll() }
        if (!waitForCallState(0, timeoutMs = 5_000) && callState() != 0) {
            // ENDCALL with no call puts the screen to sleep, so only send it while a call is up.
            shell("input keyevent KEYCODE_ENDCALL")
            waitForCallState(0)
        }
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

    /**
     * True while a ringtone is playing. Telecom plays it with usage NOTIFICATION_RINGTONE,
     * and `dumpsys audio` lists each player on one line with its state, for example
     * "ID:31 -- type:android.media.MediaPlayer -- ... state:started -- attr:AudioAttributes: usage=USAGE_NOTIFICATION_RINGTONE ..."
     * (API 29 prints "AudioPlaybackConfiguration piid:.. state:started attr:AudioAttributes: usage=..."),
     * so a silenced call shows no started ringtone player. Assumption checked against AOSP
     * dump formats, not on a device: if CI shows known callers never "ringing", look here first.
     */
    fun ringtonePlaying(): Boolean =
        shell("dumpsys audio").lineSequence().any { "USAGE_NOTIFICATION_RINGTONE" in it && "state:started" in it }

    /** Watches a ringing call for [windowMs]: true as soon as a ringtone plays. */
    fun ringsAudibly(windowMs: Long = 6_000): Boolean {
        val end = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < end) {
            if (ringtonePlaying()) return true
            Thread.sleep(250)
        }
        return false
    }

    // ---- Screen ----

    fun has(selector: BySelector): Boolean = runCatching { device.hasObject(selector) }.getOrDefault(false)
    fun has(text: String): Boolean = has(By.text(text))

    /** Polls every 50 ms (UiDevice.wait polls once a second, too coarse for the 2-second bar). */
    fun timeUntil(selector: BySelector, timeoutMs: Long = TIMEOUT, start: Long = System.currentTimeMillis()): Long? {
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (has(selector)) return System.currentTimeMillis() - start
            Thread.sleep(50)
        }
        return null
    }

    fun timeUntil(text: String, timeoutMs: Long = TIMEOUT): Long? = timeUntil(By.text(text), timeoutMs)

    fun waitFor(selector: BySelector, timeoutMs: Long = TIMEOUT): Boolean = timeUntil(selector, timeoutMs) != null
    fun waitText(text: String, timeoutMs: Long = TIMEOUT): Boolean = waitFor(By.text(text), timeoutMs)
    fun waitTextContains(text: String, timeoutMs: Long = TIMEOUT): Boolean = waitFor(By.textContains(text), timeoutMs)

    /** True when [selector] stays off screen for the whole [windowMs]. */
    fun staysAway(selector: BySelector, windowMs: Long = QUIET): Boolean = timeUntil(selector, windowMs) == null

    fun waitGone(selector: BySelector, timeoutMs: Long = TIMEOUT): Boolean = waitUntil(timeoutMs) { !has(selector) }

    fun find(selector: BySelector, timeoutMs: Long = TIMEOUT): UiObject2 {
        waitFor(selector, timeoutMs)
        return device.findObject(selector) ?: run {
            dumpUi("not found: $selector")
            error("Nothing on screen matches $selector")
        }
    }

    fun tap(text: String) = find(By.text(text)).click()
    fun tapDesc(desc: String) = find(By.desc(desc)).click()

    fun topPackage(): String = device.currentPackageName ?: ""

    fun waitForPackage(packageName: String, timeoutMs: Long = TIMEOUT) = waitUntil(timeoutMs) { topPackage() == packageName }

    fun scrollTo(selector: BySelector): Boolean {
        repeat(8) {
            if (waitFor(selector, 800)) return true
            device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.7f)
                ?: device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 20)
        }
        return has(selector)
    }

    fun scrollAndTap(selector: BySelector) {
        check(scrollTo(selector)) { dumpUi("can't scroll to $selector"); "Couldn't scroll to $selector" }
        device.findObject(selector).click()
    }

    /** Dismisses the safe-word card that follows a real conversation with an unknown number. */
    fun dismissSafeWordCard() {
        if (waitText("Everything's fine", 8_000)) tap("Everything's fine")
        waitGone(By.text("Everything's fine"))
        device.waitForIdle()
    }

    /** The full-screen pause card's headline. */
    val pauseCard: BySelector get() = By.text(PAUSE_HEADLINE)

    fun lockedCard(appLabel: String): BySelector = By.text("$appLabel is locked")
    val anyLockedCard: BySelector get() = By.textEndsWith(" is locked")

    // ---- Guardian's own screens ----

    val shieldChip: BySelector get() = By.descContains("What my family can see.")

    fun goHome() {
        device.pressHome()
        check(waitFor(shieldChip, 8_000)) { dumpUi("home not shown"); "Guardian's home screen didn't come up" }
    }

    fun chipLabel(): String? = device.findObject(shieldChip)?.contentDescription?.substringBefore(".")

    fun openTransparency() {
        goHome()
        find(shieldChip).click()
        check(waitText("What my family can see")) { "Transparency screen didn't open" }
    }

    /** Types the lock code on whichever code screen is up and taps Unlock. */
    fun enterCode(code: String = DeviceFixtures.CODE) {
        val field = find(By.desc("Lock code"))
        field.click()
        field.text = code
        device.waitForIdle()
        if (!has(By.text("Unlock"))) device.pressBack() // hide the keyboard if it covers the button
        tap("Unlock")
        val accepted = { state.unlockedUntil > System.currentTimeMillis() || !has(By.desc("Lock code")) }
        if (code == DeviceFixtures.CODE && !waitUntil(3_000, accepted)) {
            // Fall back to real key presses if ACTION_SET_TEXT didn't reach the Compose field.
            find(By.desc("Lock code")).click()
            shell("input text $code")
            if (!has(By.text("Unlock"))) device.pressBack()
            tap("Unlock")
        }
    }

    fun relock() = runBlocking { container.lock.relock() }

    /** True when a code screen ("Enter the code" page or a locked card's code box) is showing. */
    fun codeAsked(timeoutMs: Long = TIMEOUT): Boolean = waitFor(By.desc("Lock code"), timeoutMs)

    // ---- Notification shade ----

    /** Guardian's persistent notification as Android holds it (the app can read its own). */
    fun protectionNotification() = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.firstOrNull { it.id == Notifications.ID_PROTECTION }

    /**
     * Opens the shade and returns the button [label] on Guardian's notification, expanding
     * the notification first if its buttons are folded away. Buttons may be shown in
     * capitals on older Android versions, so the match ignores case.
     */
    fun notificationAction(label: String): UiObject2? {
        device.openNotification()
        val title = By.text(Pattern.compile("Guardian Mode is (on|paused)"))
        if (!waitFor(title, 8_000)) return null
        val action = By.text(Pattern.compile(Pattern.quote(label), Pattern.CASE_INSENSITIVE))
        if (waitFor(action, 1_500)) return device.findObject(action)
        val titleObj = device.findObject(title) ?: return null
        var node: UiObject2? = titleObj
        repeat(6) {
            val expand = node?.findObject(By.res("android:id/expand_button"))
            if (expand != null) {
                expand.click()
                if (waitFor(action, 2_000)) return device.findObject(action)
                return@repeat
            }
            node = node?.parent
        }
        titleObj.swipe(Direction.DOWN, 1f)
        return if (waitFor(action, 2_000)) device.findObject(action) else null
    }

    fun closeShade() {
        device.pressBack()
        device.waitForIdle()
    }

    // ---- Diagnostics ----

    fun resumedActivity(): Activity? {
        var found: Activity? = null
        instrumentation.runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
        }
        return found
    }

    /** Guardian's own log lines (this process), as `adb logcat --pid` would show them. */
    fun ownLogcat(): String = shell("logcat -d -v brief --pid=${Process.myPid()}")

    fun dumpUi(reason: String) {
        val out = java.io.ByteArrayOutputStream()
        runCatching { device.dumpWindowHierarchy(out) }
        Log.i("UiDump", "---- $reason ----")
        out.toString().chunked(3_000).forEach { Log.i("UiDump", it) }
    }

    companion object {
        const val TIMEOUT = 8_000L
        /** How long to watch for something that must not appear. */
        const val QUIET = 5_000L
        /** Time for a phone-state broadcast to reach the brain after the registry changes. */
        const val SETTLE = 1_000L
        /** The spec's bar for the pause card (bar 3) and the measured cases GM-PAUSE-040 / GM-PERF-003. */
        const val CARD_BAR_MS = 2_000L
        const val PAUSE_HEADLINE = "Hold on a minute."
        const val SAFE_WORD_HEADLINE = "Did the caller say they're family?"
    }
}
