package app.guardianmode.support

import android.Manifest
import android.os.Build
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.media.AudioManager
import android.os.Process
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.provider.ContactsContract
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
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
        syncProtectionNotification()
        device.pressHome()
        device.waitForIdle()
    }

    override fun failed(e: Throwable, description: Description) {
        dumpUi("${description.methodName} failed")
        // One line per failure that CI turns into an annotation (qa/daily/run-device-suite.sh).
        runCatching { Log.i(DIAG, "${description.className.substringAfterLast('.')}.${description.methodName}: ${diagnostics()}") }
            .onFailure { Log.w(DIAG, "${description.methodName}: diagnostics failed: $it") }
    }

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

    /**
     * Starts each test with the persistent notification matching the saved state, as a fresh
     * start of Guardian would post it (GuardianService.onCreate). A test that pauses and resumes
     * quickly can leave it out of step with the state (as NOTIF-STALE once did, see
     * GM-SVC-014); without this, the next test that reads the notification would fail for a
     * bug it isn't about (gm_svc_001 after gm_svc_005 on PR #10's API 36 run).
     */
    private fun syncProtectionNotification() {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!state.onboardingDone || protectionNotification() == null) return
        val paused = state.isPaused(System.currentTimeMillis())
        val want = if (paused) "Guardian Mode is paused" else "Guardian Mode is on"
        val title = { protectionNotification()?.notification?.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() }
        if (waitUntil(2_000) { title() == want }) return
        Log.w(TAG, "protection notification says \"${title()}\" but the saved state is ${if (paused) "paused" else "on"}; posting it again from the state")
        nm.notify(Notifications.ID_PROTECTION, Notifications.protection(context, paused))
        waitUntil(2_000) { title() == want }
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
        ensureHomeResolves()
    }

    /** What Home opens right now: a component, or the system's chooser when no home app is set. */
    fun homeResolvesTo(): String =
        shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
            .trim().lines().lastOrNull()?.trim().orEmpty()

    /**
     * Holding the Home role is what makes Home open Guardian. A test that takes the role away
     * (gm_lock_041) or hands it to another launcher (gm_home_015) is followed by one that adds
     * it back, and on API 33 that can leave the role held but no preferred home set: every Home
     * press then shows Android's chooser and Guardian comes back only by itself 5 s later (the
     * API 33 run of f42747b: GM-PERF-002 took 5026-5044 ms on all ten presses, the chooser,
     * package "android", resumed between them). So this waits until Home resolves to Guardian
     * and sets the home activity directly if the role alone didn't.
     */
    private fun ensureHomeResolves() {
        if (waitUntil(3_000) { homeResolvesTo().startsWith("$pkg/") }) return
        Log.w(TAG, "Home resolves to ${homeResolvesTo()} after adding the Home role; setting Guardian's home activity")
        shell("cmd package set-home-activity $pkg/app.guardianmode.ui.MainActivity")
        if (!waitUntil(3_000) { homeResolvesTo().startsWith("$pkg/") }) Log.w(TAG, "Home still resolves to ${homeResolvesTo()}")
    }

    /**
     * Turns the screen on and gets past the lock screen, as a parent unlocking would. On a busy
     * emulator one dismiss-keyguard can be dropped, and an app opened then stays behind the lock
     * screen without ever coming to the front, so this waits until the lock screen is really gone.
     */
    fun wake() {
        shell("input keyevent KEYCODE_WAKEUP")
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000
        do {
            shell("wm dismiss-keyguard")
            Thread.sleep(500)
        } while (keyguard.isKeyguardLocked && android.os.SystemClock.uptimeMillis() < deadline)
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
        trace("opening ${app.label}")
        lastOpenAt = System.currentTimeMillis()
        shell("am start -n ${app.component} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER")
    }

    /** A line in logcat (tag GuardianTrace) with what the brain remembers, for reading a failed run afterwards. */
    fun trace(what: String) {
        runCatching { Log.i(TRACE, "$what: top=${topPackage()} callState=${callState()} ${GuardianReset.describe(container)}") }
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
        if (callState() != 0) {
            Log.w(TAG, "a call was still up before ringing ${number.takeLast(4)}; ending it")
            hangUpEverything()
        }
        EmulatorConsole.incomingCall(number)
        return waitForCallState(1)
    }

    /** Rings and answers, leaving the call up. */
    fun startAnsweredCall(number: String) {
        check(ring(number)) { "Call from $number never rang" }
        answer(number)
        check(waitForCallState(2)) { "Call from $number wasn't answered" }
        Thread.sleep(SETTLE) // let PhoneStateReceiver hand OFFHOOK to the brain
        trace("answered ${number.takeLast(4)}")
    }

    /** A real incoming call through the emulator's telephony, answered and kept up for [talkMillis]. */
    fun answeredCall(number: String, talkMillis: Long, hangUp: Boolean = true) {
        startAnsweredCall(number)
        Thread.sleep(talkMillis)
        if (hangUp) hangUp(number)
    }

    /**
     * Answers the ringing call. The console's `gsm accept` makes the modem's call active, and
     * on Android 13+ Telecom follows. Android 10 is answered differently: see [answerOnQ].
     */
    fun answer(number: String) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            answerOnQ(number)
            return
        }
        runCatching { EmulatorConsole.accept(number) }
        waitForCallState(2, timeoutMs = 3_000)
        if (!waitUntil(1_500) { !incomingCallShown() }) {
            Log.w(TAG, "incoming-call heads-up still up after gsm accept; answering through Telecom")
            telecomAccept()
            if (!waitUntil(3_000) { !incomingCallShown() }) shell("input keyevent KEYCODE_CALL")
            waitUntil(3_000) { !incomingCallShown() }
        }
        if (callState() != 2) shell("input keyevent KEYCODE_CALL")
        trace("answer ${number.takeLast(4)}: incomingShown=${incomingCallShown()}")
    }

    /**
     * Android 10. [ring] returns as soon as the modem rings (the registry says RINGING), while
     * Telecom is still asking Guardian's call screener about the call. A `gsm accept` then makes
     * the modem's call active underneath a Telecom call that hasn't started ringing yet, and on
     * Android 10 Telecom never catches up: the heads-up keeps offering Answer, Guardian never
     * gets OFFHOOK (its tracker stays at RINGING, so the call never counts as answered and no
     * card follows), and answering through Telecom afterwards fails because the modem has no
     * ringing call left. Every API 29 trace on 64f203e showed exactly that: registry 2, modem
     * "active", heads-up still up, tracker RINGING. (The host checks wait 2 s before
     * `gsm accept` and pass.) The shell's ANSWER_PHONE_CALLS can't be borrowed on Android 10
     * either ("Neither user ... nor current process has ANSWER_PHONE_CALLS").
     *
     * So the call is answered the way a person does: wait until Telecom rings it, then the CALL
     * key (PhoneWindowManager answers Telecom's ringing call), then the heads-up's Answer
     * button; Telecom tells the modem. The console is only the last resort.
     */
    private fun answerOnQ(number: String) {
        val ringing = waitUntil(8_000) { telecomCallState() == 1 || incomingCallShown() }
        if (!ringing) Log.w(TAG, "Telecom never showed the call from ${number.takeLast(4)} ringing; answering anyway")
        val answered = { telecomCallState() == 2 && !incomingCallShown() }
        shell("input keyevent KEYCODE_CALL")
        if (!waitUntil(4_000, answered)) {
            Log.w(TAG, "CALL key didn't answer; tapping the heads-up's Answer")
            val button = device.findObject(By.pkg("com.android.systemui").desc("Answer"))
                ?: device.findObject(By.pkg("com.android.systemui").text(Pattern.compile("Answer", Pattern.CASE_INSENSITIVE)))
            button?.click()
            if (!waitUntil(4_000, answered)) {
                Log.w(TAG, "Answer button didn't answer either; accepting through the console")
                runCatching { EmulatorConsole.accept(number) }
                waitUntil(3_000, answered)
            }
        }
        waitForCallState(2, timeoutMs = 3_000)
        trace("answer ${number.takeLast(4)} (Android 10): telecom=${telecomCallState()} incomingShown=${incomingCallShown()} gsm=${gsmList()}")
    }

    /**
     * The modem's calls on one line, numbers cut to their last 4 digits: this goes to Guardian's
     * own logcat, which gm_priv_004 searches for the caller's number.
     */
    fun gsmList(): String = runCatching {
        EmulatorConsole.list().trim().replace("\n", "; ").replace(Regex("\\+?\\d{6,}")) { "…" + it.value.takeLast(4) }
    }.getOrDefault("?")

    /**
     * The call state as Guardian's own process gets it from TelephonyManager: 0 idle, 1 ringing,
     * 2 off-hook, -1 unreadable. On Android 10 this is Telecom's view (the one the PHONE_STATE
     * broadcasts follow), which can differ from the registry's [callState].
     */
    fun telecomCallState(): Int = runCatching {
        @Suppress("DEPRECATION")
        context.getSystemService(android.telephony.TelephonyManager::class.java).callState
    }.getOrDefault(-1)

    /** Telecom's acceptRingingCall with the shell's ANSWER_PHONE_CALLS, as adb could. */
    private fun telecomAccept() {
        runCatching {
            asShell(Manifest.permission.ANSWER_PHONE_CALLS) {
                @Suppress("DEPRECATION")
                context.getSystemService(android.telecom.TelecomManager::class.java).acceptRingingCall()
            }
        }.onFailure { Log.w(TAG, "acceptRingingCall failed: $it"); trace("acceptRingingCall failed: $it") }
    }

    /**
     * Whether the phone still shows the call as ringing: the incoming-call heads-up's Answer
     * button. (TelecomManager.isRinging is a system API, and the registry follows the modem.)
     */
    fun incomingCallShown(): Boolean =
        device.hasObject(By.pkg("com.android.systemui").desc("Answer")) ||
            device.hasObject(By.pkg("com.android.systemui").text("Incoming call"))

    fun hangUp(number: String) {
        runCatching { EmulatorConsole.hangUp(number) }
        if (!waitForCallState(0)) hangUpEverything()
        Thread.sleep(SETTLE) // let PhoneStateReceiver hand IDLE to the brain
        trace("hung up ${number.takeLast(4)}")
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

    /**
     * Ends every call, however it was made. The modem doesn't always list calls the phone
     * placed, so Telecom is asked too (with the shell's ANSWER_PHONE_CALLS, as adb could),
     * and ENDCALL is the last resort. A call left up makes the next test's incoming call
     * "waiting" instead of ringing.
     */
    fun hangUpEverything() {
        if (EmulatorConsole.available) runCatching { EmulatorConsole.hangUpAll() }
        if (waitForCallState(0, timeoutMs = 3_000)) return
        repeat(3) { attempt ->
            runCatching {
                asShell(Manifest.permission.ANSWER_PHONE_CALLS) {
                    @Suppress("DEPRECATION")
                    context.getSystemService(android.telecom.TelecomManager::class.java).endCall()
                }
            }
            if (waitForCallState(0, timeoutMs = 3_000)) return
            // ENDCALL with no call puts the screen to sleep, so only send it while a call is up.
            if (callState() != 0) shell("input keyevent KEYCODE_ENDCALL")
            if (waitForCallState(0, timeoutMs = 3_000)) return
            if (EmulatorConsole.available) runCatching { EmulatorConsole.hangUpAll() }
            Log.w(TAG, "call still up after hang-up try ${attempt + 1}: state=${callState()} gsm=${runCatching { EmulatorConsole.list().trim() }.getOrDefault("?")}")
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


    /**
     * Calls in the phone's call log since [sinceMs], newest first, as (number, type) where
     * type 1 is incoming, 2 outgoing, 3 missed. Read with the shell's `content` tool, as adb
     * would: the test process runs as Guardian, which has no call-log permission (and on
     * Android 10 the provider also checks Guardian's own app-op, so borrowing the shell's
     * permission isn't enough).
     */
    fun callLog(sinceMs: Long): List<Pair<String, Int>> =
        Regex("number=([^,]*), type=(\\d+), date=(\\d+)")
            .findAll(callLogQuery("number:type:date"))
            .filter { it.groupValues[3].toLong() >= sinceMs - 2_000 }
            .sortedByDescending { it.groupValues[3].toLong() }
            .map { it.groupValues[1] to it.groupValues[2].toInt() }
            .toList()

    /**
     * The shell's `content query` of the call log. Its errors go to stderr, which a test can't
     * read, so an answer that is neither rows nor "No result found." means the shell couldn't
     * read the call log at all (on API 29: "Permission Denial ... requires READ_CALL_LOG", which
     * run-device-suite.sh prints before the tests); it is then read as root, and if that fails
     * too it is logged instead of silently reading as an empty call log.
     */
    fun callLogQuery(projection: String): String {
        val query = "content query --uri content://call_log/calls --projection $projection"
        val readable = { out: String -> "Row:" in out || "No result" in out }
        val out = shell(query)
        if (readable(out)) return out
        // Android 10's shell doesn't request READ_CALL_LOG at all ("Package com.android.shell has
        // not requested permission"), so it can't be granted one; root can read it, as `adb root`
        // could (the emulator images let the shell run su).
        val asRoot = shell("su 0 $query")
        if (readable(asRoot)) return asRoot
        Log.w(TAG, "the call log can't be read, as the shell or as root: \"${(out + asRoot).trim().take(200)}\"")
        return out
    }

    /** Outgoing calls to [number] (any formatting) logged since [sinceMs]. */
    fun dialedCount(number: String, sinceMs: Long): Int {
        val want = number.filter(Char::isDigit).takeLast(10)
        return callLog(sinceMs).count { (n, type) -> type == CALL_OUTGOING && n.filter(Char::isDigit).endsWith(want) }
    }

    /**
     * True when the phone placed a call to [number] since [sinceMs]. The emulator's modem
     * doesn't reliably list calls the phone places (`gsm list`), so this waits for the call
     * to start (modem or telephony off-hook), ends every call so the call log is written,
     * and reads the call log.
     */
    fun placedCallTo(number: String, sinceMs: Long, timeoutMs: Long = 15_000): Boolean {
        waitUntil(timeoutMs) { EmulatorConsole.available && EmulatorConsole.hasCallWith(number) || callState() == 2 || dialedCount(number, sinceMs) > 0 }
        hangUpEverything()
        val placed = waitUntil(10_000) { dialedCount(number, sinceMs) > 0 }
        if (!placed) Log.w(TAG, "no outgoing call to …${number.takeLast(4)} logged; call log since start: ${callLog(sinceMs).map { (n, t) -> "…${n.takeLast(4)}/$t" }}")
        return placed
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

    /**
     * Taps what [selector] matches the way a person would: the keyboard is put away first
     * (it covers the bottom of the screen, and UiAutomator still reports what's under it),
     * then the screen is scrolled until the target shows. Compose puts a button's text on a
     * child of the clickable node, so when several nodes match, the one inside a clickable
     * one wins (the "Stop using Guardian Mode" heading vs. its button).
     */
    fun tapOn(selector: BySelector) {
        waitFor(selector, 3_000)
        hideKeyboard()
        check(seen(selector, 500)) { dumpUi("not found: $selector"); "Nothing on screen matches $selector" }
        var matches = device.findObjects(selector)
        // Only a heading with the same words in view on one of Guardian's pages: the button
        // is further down. (Never scrolled outside Guardian: in the shade a scroll closes it.)
        repeat(3) {
            if (matches.isEmpty() || matches.any { insideClickable(it) } || !scrollOnce(Direction.DOWN)) return@repeat
            matches = device.findObjects(selector)
        }
        val target = matches.firstOrNull { insideClickable(it) } ?: matches.firstOrNull()
            ?: run { dumpUi("gone: $selector"); error("Nothing on screen matches $selector") }
        // Compose can replace the node between finding and tapping it (StaleObjectException:
        // gm_scrn_006 on API 33, then gm_tran_004 on the API 33 run of f42747b with three stale
        // finds in a row while the switch rows redrew). So only its place is read from the node,
        // finding it again if that goes stale, and the tap goes to that place on screen, as a
        // finger would; UiObject2.click taps the same spot.
        var current = target
        repeat(5) { attempt ->
            val center = try {
                current.visibleCenter
            } catch (e: androidx.test.uiautomator.StaleObjectException) {
                if (attempt == 4) throw e
                Log.w(TAG, "tap target went stale, finding it again: $selector")
                device.waitForIdle(500)
                val again = device.findObjects(selector)
                current = again.firstOrNull { insideClickable(it) } ?: again.firstOrNull() ?: throw e
                return@repeat
            }
            device.click(center.x, center.y)
            device.waitForIdle()
            return
        }
    }

    fun tap(text: String) = tapOn(By.text(text))
    fun tapDesc(desc: String) = tapOn(By.desc(desc))

    private fun insideClickable(obj: UiObject2): Boolean = runCatching {
        var node: UiObject2? = obj
        repeat(6) {
            val n = node ?: return@runCatching false
            if (n.isClickable || n.isCheckable) return@runCatching true
            node = n.parent
        }
        false
    }.getOrDefault(false)

    /**
     * True when [selector] is on screen or comes into view by scrolling (down first, then
     * back up). Cards and pages scroll, and at the large text size the lower buttons start
     * below the fold, where UiAutomator doesn't report them.
     */
    fun seen(selector: BySelector, waitMs: Long = 2_000): Boolean {
        if (waitFor(selector, waitMs)) return true
        // The keyboard shrinks what a card or page can show; put it away before looking further.
        hideKeyboard()
        if (has(selector)) return true
        // Down to the end of the page, then back up to the top.
        var moves = 0
        for (direction in listOf(Direction.DOWN, Direction.UP)) {
            repeat(25) {
                val moved = scrollOnce(direction)
                if (moved) moves++
                if (has(selector)) return true
                if (!moved) return@repeat
            }
        }
        // Says which it was: nothing of Guardian's that scrolls (another window on top, the page not
        // drawn yet) or a page scrolled end to end without it (gm_racc_014 on API 33, 589a1b6).
        val scrollable = runCatching { device.hasObject(By.pkg(pkg).scrollable(true)) }.getOrDefault(false)
        Log.w(TAG, "not seen after $moves scroll moves: $selector (top=${topPackage()}, Guardian scrollable on screen=$scrollable)")
        return false
    }

    /**
     * Scrolls Guardian's own scrolling page or card one step; false when there is none or
     * it can't go further that way. Other apps' screens (and the notification shade, which a
     * swipe would close) are never scrolled.
     */
    private fun scrollOnce(direction: Direction): Boolean {
        val scrollable = runCatching { device.findObject(By.pkg(pkg).scrollable(true)) }.getOrNull() ?: return false
        val more = runCatching { scrollable.scroll(direction, 0.6f) }
            .onFailure { Log.w(TAG, "scroll $direction threw $it") }
            .getOrDefault(false)
        device.waitForIdle(500)
        return more
    }

    /**
     * Whether the switch described as [desc] shows on, read from the accessibility tree:
     * the checked state of the switch node (or a checkable node right around it), else the
     * "On"/"Off" state text Guardian gives it. Null when no such switch is on screen.
     */
    fun switchOn(desc: String): Boolean? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        val node = findNode(root) { it.contentDescription?.toString() == desc } ?: return null
        val checkable = sequenceOf(node, node.parent, node.parent?.parent).filterNotNull().firstOrNull { it.isCheckable }
            ?: findNode(node, maxDepth = 3) { it.isCheckable }
        if (checkable != null) return checkable.isChecked
        return when (AccessibilityNodeInfoCompat.wrap(node).stateDescription?.toString()) {
            "On" -> true
            "Off" -> false
            else -> null
        }
    }

    private fun findNode(node: AccessibilityNodeInfo, maxDepth: Int = 40, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (match(node)) return node
        if (maxDepth == 0) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNode(child, maxDepth - 1, match)?.let { return it }
        }
        return null
    }

    /** True while the on-screen keyboard is up. */
    fun keyboardShown(): Boolean {
        val windows = runCatching { instrumentation.uiAutomation.windows }.getOrDefault(emptyList())
        if (windows.isNotEmpty()) {
            return windows.any { w ->
                w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                    android.graphics.Rect().also { w.getBoundsInScreen(it) }.let { it.height() > 0 && it.width() > 0 }
            }
        }
        return shell("dumpsys input_method").contains("mInputShown=true")
    }

    /** Puts the keyboard away if it's up (Back with the keyboard up only closes the keyboard). */
    fun hideKeyboard() {
        if (!keyboardShown()) return
        device.pressBack()
        waitUntil(2_000) { !keyboardShown() }
    }

    /** Back, as the parent means it: the keyboard goes away first if it's up, then the screen goes back. */
    fun back() {
        // A code screen focuses its field and the keyboard slides up a moment later; let it
        // settle so this Back isn't spent closing a keyboard that wasn't up yet.
        device.waitForIdle()
        if (has(By.desc("Lock code"))) waitUntil(1_500) { keyboardShown() }
        hideKeyboard()
        device.pressBack()
        device.waitForIdle()
    }

    fun topPackage(): String = device.currentPackageName ?: ""

    fun waitForPackage(packageName: String, timeoutMs: Long = TIMEOUT) = waitUntil(timeoutMs) { topPackage() == packageName }

    fun scrollTo(selector: BySelector): Boolean = seen(selector, 800)

    fun scrollAndTap(selector: BySelector) = tapOn(selector)

    /** Dismisses the safe-word card that follows a real conversation with an unknown number. */
    fun dismissSafeWordCard() {
        if (waitText("Everything's fine", 8_000)) tap("Everything's fine")
        waitGone(By.text("Everything's fine"))
        device.waitForIdle()
    }

    // Card selectors are pinned to Guardian's own windows: a heads-up notification carries
    // the same words ("Settings is locked") and must not count as the card itself.

    /** The full-screen pause card's headline. */
    val pauseCard: BySelector get() = By.pkg(pkg).text(PAUSE_HEADLINE)

    fun lockedCard(appLabel: String): BySelector = By.pkg(pkg).text("$appLabel is locked")
    val anyLockedCard: BySelector get() = By.pkg(pkg).textEndsWith(" is locked")

    // ---- Guardian's own screens ----

    val shieldChip: BySelector get() = By.descContains("What my family can see.")

    fun goHome() {
        device.pressHome()
        check(waitFor(shieldChip, 8_000)) { dumpUi("home not shown"); "Guardian's home screen didn't come up" }
    }

    fun chipLabel(): String? = device.findObject(shieldChip)?.contentDescription?.substringBefore(".")

    fun openTransparency() {
        // A heads-up notification can sit over the chip for a few seconds, so try again once it's gone.
        repeat(3) { attempt ->
            goHome()
            find(shieldChip).click()
            if (waitText("What my family can see", if (attempt < 2) 5_000 else TIMEOUT)) return
            Log.w(TAG, "transparency screen didn't open (try ${attempt + 1}); top=${topPackage()}")
        }
        dumpUi("transparency screen didn't open")
        error("Transparency screen didn't open")
    }

    /** Types the lock code on whichever code screen is up and taps Unlock. */
    fun enterCode(code: String = DeviceFixtures.CODE) {
        val field = find(By.desc("Lock code"))
        field.click()
        field.text = code
        device.waitForIdle()
        tap("Unlock") // puts the keyboard away first
        val accepted = { state.unlockedUntil > System.currentTimeMillis() || !has(By.desc("Lock code")) }
        if (code == DeviceFixtures.CODE && !waitUntil(3_000, accepted)) {
            // Fall back to real key presses if ACTION_SET_TEXT didn't reach the Compose field.
            find(By.desc("Lock code")).click()
            shell("input text $code")
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

    /** What the test and Guardian see right now, on one line, for failure reports. */
    fun diagnostics(): String = buildString {
        val now = System.currentTimeMillis()
        append("top=${topPackage()} resumed=${resumedActivity()?.javaClass?.simpleName} callState=${callState()} telecom=${telecomCallState()} ")
        append("screenOn=${isScreenOn()} keyboard=${keyboardShown()} ")
        append("gsm=[${gsmList()}] ")
        append("brain{${runCatching { GuardianReset.describe(container) }.getOrElse { "?$it" }}} ")
        append("shared=${sharedTypes()} paused=${state.isPaused(now)} lock=${state.config.lock.level}/${if (state.lockCodeHash.isEmpty()) "no code" else "code"} ")
        append("missing=${runCatching { container.capabilities.missing().map { it.key } }.getOrDefault(emptyList())} ")
        append("resumedEvents=${recentResumes(30_000)} ")
        val calls = runCatching { callLog(now - 120_000) }.getOrDefault(emptyList())
        append("calls=${calls.map { (n, t) -> "${n.takeLast(4)}/$t" }} ")
        if (calls.isEmpty()) {
            // Tells "nothing logged" apart from "the provider wouldn't answer the shell".
            val raw = runCatching { callLogQuery("type:date") }.getOrElse { "?$it" }
            append("callLogRaw=${raw.lines().size} lines: ${raw.trim().takeLast(160).replace("\n", " / ")} ")
        }
        // By the line's own tag ("W/DeviceRule( 123): ..."), not words in it: stack traces other
        // tags print mention DeviceRule on every line.
        val log = ownLogcat().lines().filter { line ->
            val tag = line.drop(2).substringBefore('(').trim()
            (tag in DIAG_TAGS || tag == "UiDevice" && "\tat " !in line) &&
                (line.startsWith("W/") || line.startsWith("E/") || "Card" in line)
        }.takeLast(8)
        append("log=$log ")
        append("screen=${runCatching { screenSummary().take(1_500) }.getOrDefault("?")}")
    }

    /** Apps brought to the front in the last [windowMs], as Usage access reports them to Guardian. */
    fun recentResumes(windowMs: Long): List<String> = runCatching {
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val events = usage.queryEvents(now - windowMs, now)
        val e = UsageEvents.Event()
        val out = mutableListOf<String>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) out += "${e.packageName.substringAfterLast('.')}@-${(now - e.timeStamp) / 100 / 10.0}s"
        }
        out.takeLast(12)
    }.getOrDefault(emptyList())

    /**
     * What's on screen, compactly (one entry per node with text or a description), to
     * logcat tag UiDump. Kept short on purpose: whole hierarchy dumps filled the log buffer
     * and pushed out the traces the failure report needs.
     */
    fun dumpUi(reason: String) = Log.i("UiDump", "---- $reason ---- ${screenSummary()}")

    fun screenSummary(): String {
        val out = java.io.ByteArrayOutputStream()
        runCatching { device.dumpWindowHierarchy(out) }
        val nodes = Regex("<node [^>]*>").findAll(out.toString()).mapNotNull { m ->
            fun attr(name: String) = Regex("$name=\"([^\"]*)\"").find(m.value)?.groupValues?.get(1).orEmpty()
            val text = attr("text").ifBlank { attr("content-desc") }
            if (text.isBlank()) null else "${attr("package").substringAfterLast('.')}:${text.take(60)}${if (attr("clickable") == "true") "*" else ""}"
        }.toList()
        return nodes.joinToString(" | ").take(3_500)
    }

    companion object {
        private const val TAG = "DeviceRule"
        /** Logcat tag of the one-line failure diagnostics CI reports. */
        const val DIAG = "GuardianDiag"
        const val TRACE = "GuardianTrace"
        private val DIAG_TAGS = listOf("GuardianBrain", "CardLauncher", "GuardianScreening", "GuardianService", "AndroidRuntime", TAG)
        const val CALL_OUTGOING = 2
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
