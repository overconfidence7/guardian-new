package app.guardianmode.device

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.TrafficStats
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import android.system.Os
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import app.guardianmode.core.EventType
import app.guardianmode.support.DeviceFixtures
import app.guardianmode.support.DeviceFixtures.UNKNOWN
import app.guardianmode.support.DeviceRule
import app.guardianmode.support.StubApp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * qa/antigravity/cases/14-privacy-security-policy.md, the parts a device can check:
 * what Guardian writes to its own logs and files, whether other apps (the shell stands in
 * for them, as adb does) can reach its private components, and the share target.
 */
@RunWith(AndroidJUnit4::class)
class PrivacyDeviceTest {
    @get:Rule val rule = DeviceRule()

    private val unknownForms = listOf(UNKNOWN, "555-999-8888", "(555) 999-8888", "9998888")

    @Test fun gm_priv_004_gm_call_015_gm_scrn_012_noNumberOrSafeWordInLogsOrFiles() {
        rule.setState(DeviceFixtures.familyState)
        rule.shell("logcat -c")
        rule.answeredCall(UNKNOWN, talkMillis = 32_000)
        assertThat(rule.waitText(DeviceRule.SAFE_WORD_HEADLINE)).isTrue()
        rule.tap("Show our safe word")
        // (Selectors never name the word itself, so the test can't put it in the log it checks.)
        assertThat(rule.waitTextContains("Your safe word is")).isTrue()
        rule.tap("Everything's fine")
        rule.open(StubApp.CHASE)
        assertThat(rule.waitFor(rule.pauseCard)).isTrue()
        rule.tap("I'm fine, continue")
        Thread.sleep(2_000)

        val log = rule.ownLogcat()
        for (secret in unknownForms + "bluebird" + DeviceFixtures.SARAH + "555-010-1111") {
            assertWithMessage("Guardian's logcat mentions $secret").that(log.contains(secret)).isFalse()
        }

        // The safe word belongs only in the config the family wrote, never in an event.
        val events = rule.state.sharedEvents + rule.state.outbox
        assertThat(events.joinToString { it.data.values.joinToString() }).doesNotContain("bluebird")

        // No file Guardian keeps holds the stranger's number.
        val files = ownFiles().filter { it.isFile && it.length() < 5_000_000 }
        assertThat(files).isNotEmpty()
        for (file in files) {
            val text = runCatching { file.readBytes().toString(Charsets.ISO_8859_1) }.getOrDefault("")
            for (form in unknownForms) assertWithMessage("${file.path} holds $form").that(text.contains(form)).isFalse()
        }
    }

    /**
     * GM-PRIV-001: in solo mode Guardian connects nowhere while it does its job: a long call
     * from a stranger (recorded as an event, which is what must not leave), a pause card during
     * it, the safe-word card after it and a remote-control warning. Every socket Guardian's uid
     * holds is read from /proc/net as the shell (as `netstat` does) every 250 ms. The only ones
     * allowed are the test's own emulator console (10.0.2.2:5554) and HTTPS to Google, which is
     * where Firebase's start-up attempts go; anything else fails, and so does any connection to
     * the Firebase emulators on 10.0.2.2, which is where this build would send events. Setup in
     * solo mode never calls the cloud (CloudSync does nothing without a pairing).
     */
    @Test fun gm_priv_001_soloModeConnectsNowhere() {
        rule.requireConsole()
        rule.requireStubApk(StubApp.ANYDESK)
        rule.setState(DeviceFixtures.soloState.let {
            it.copy(config = it.config.copy(protection = it.config.protection.copy(longCallAlert = it.config.protection.longCallAlert.copy(minutes = 1))))
        })
        val uid = Process.myUid()
        check(readSockets(uid) != null) { "couldn't read /proc/net as the shell" }
        Thread.sleep(3_000)
        val seen = Collections.synchronizedSet(linkedSetOf<String>())
        val watching = AtomicBoolean(true)
        val watcher = thread(name = "gm-priv-001-sockets") {
            while (watching.get()) {
                readSockets(uid)?.let { seen += it }
                Thread.sleep(250)
            }
        }
        val txBefore = TrafficStats.getUidTxBytes(uid)
        val rxBefore = TrafficStats.getUidRxBytes(uid)
        try {
            rule.startAnsweredCall(UNKNOWN)
            rule.open(StubApp.CHASE)
            assertThat(rule.waitFor(rule.pauseCard)).isTrue()
            rule.tap("I'm fine, continue")
            assertWithMessage("long-call event recorded").that(rule.waitForEvent(EventType.LONG_UNKNOWN_CALL, timeoutMs = 90_000)).isNotNull()
            rule.hangUp(UNKNOWN)
            rule.dismissSafeWordCard()
            assertThat(rule.installStub(StubApp.ANYDESK)).contains("Success")
            assertWithMessage("remote-control warning").that(rule.waitText("AnyDesk can let someone control your phone.", 15_000)).isTrue()
            rule.tap("I trust this app")
            Thread.sleep(5_000)
        } finally {
            watching.set(false)
            watcher.join(5_000)
        }
        val tx = TrafficStats.getUidTxBytes(uid) - txBefore
        val rx = TrafficStats.getUidRxBytes(uid) - rxBefore
        assertWithMessage("events recorded during the run").that(rule.state.sharedEvents.map { it.type }).containsAtLeast(EventType.LONG_UNKNOWN_CALL, EventType.PAUSE_SHOWN)
        assertWithMessage("solo mode has nothing to upload to").that(rule.state.pairing).isNull()

        val endpoints = seen.toSortedSet()
        val described = endpoints.associateWith { endpoint ->
            val (host, port) = endpoint.substringAfter(' ').let { it.substringBeforeLast(':') to it.substringAfterLast(':').toInt() }
            val name = if (host == CONSOLE_HOST && port == CONSOLE_PORT) "the test's emulator console" else runCatching { InetAddress.getByName(host).canonicalHostName }.getOrDefault(host)
            "$endpoint ($name)"
        }
        Log.i(TAG, "GM-PRIV-001 Guardian's uid sent $tx and received $rx bytes (the console included); endpoints: ${described.values.ifEmpty { listOf("none") }}")
        val firebaseEmulators = endpoints.filter { it.substringAfter(' ').startsWith("$CONSOLE_HOST:") && !it.endsWith(":$CONSOLE_PORT") }
        assertWithMessage("connections to the Firebase emulators (where this build sends events)").that(firebaseEmulators).isEmpty()
        val unexpected = endpoints.filterNot { endpoint ->
            val port = endpoint.substringAfterLast(':')
            val name = described.getValue(endpoint)
            endpoint.endsWith("$CONSOLE_HOST:$CONSOLE_PORT") ||
                (port == "443" && GOOGLE.any { name.contains(it) })
        }
        assertWithMessage("connections other than Firebase start-up to Google: ${unexpected.map { described.getValue(it) }}").that(unexpected).isEmpty()
    }

    /**
     * Remote ends of every TCP and UDP socket [uid] holds right now, as "tcp 1.2.3.4:443", or
     * null if /proc/net can't be read. Sockets with no remote end (listening, unconnected) and
     * loopback are left out.
     */
    private fun readSockets(uid: Int): Set<String>? {
        val out = mutableSetOf<String>()
        var tables = 0
        for (proto in listOf("tcp", "tcp6", "udp", "udp6")) {
            val text = rule.shell("cat /proc/net/$proto")
            if (!text.trimStart().startsWith("sl")) continue
            tables++
            for (line in text.lines().drop(1)) {
                val f = line.trim().split(Regex("\\s+"))
                if (f.size < 8 || f[7].toIntOrNull() != uid) continue
                val remote = decode(f[2]) ?: continue
                if (remote.startsWith("127.") || remote.startsWith("[::1]") || remote.endsWith(":0")) continue
                out += "${proto.removeSuffix("6")} $remote"
            }
        }
        return if (tables == 0) null else out
    }

    /** "0100007F:1F90" → "127.0.0.1:8080"; IPv6 too, with IPv4-mapped addresses as IPv4. */
    private fun decode(hexEndpoint: String): String? = runCatching {
        val (addr, portHex) = hexEndpoint.split(':')
        val port = portHex.toInt(16)
        // /proc/net writes each 32-bit word of the address in the kernel's (little-endian) order.
        val bytes = addr.chunked(8).flatMap { word -> word.chunked(2).map { it.toInt(16).toByte() }.reversed() }.toByteArray()
        val ip = InetAddress.getByAddress(bytes)
        val text = if (ip is Inet6Address && bytes.take(10).all { it == 0.toByte() } && bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()) {
            InetAddress.getByAddress(bytes.copyOfRange(12, 16)).hostAddress
        } else {
            ip.hostAddress
        }
        if (ip.isAnyLocalAddress) "$text:0" else if (ip is Inet6Address && !text!!.contains('.')) "[$text]:$port" else "$text:$port"
    }.getOrNull()

    @Test fun gm_priv_005_noBackups() {
        val info = rule.context.packageManager.getApplicationInfo(rule.pkg, 0)
        assertThat(info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP).isEqualTo(0)
        val out = rule.shell("bmgr backupnow ${rule.pkg}")
        Log.i(TAG, "GM-PRIV-005 bmgr backupnow: ${out.trim()}")
        assertThat(out).doesNotContain("Success")
    }

    @Test fun gm_priv_006_filesArePrivate() {
        // Make sure there is something on disk to check.
        rule.updateState { it.copy(lastHeartbeatAt = System.currentTimeMillis()) }
        val data = rule.context.dataDir
        val everything = ownFiles()
        assertThat(everything.any { it.name == "guardian_state.json" }).isTrue()
        for (f in everything) {
            val mode = Os.lstat(f.path).st_mode
            assertWithMessage("${f.path} is not world-readable").that(mode and 0b000_000_100).isEqualTo(0)
            assertWithMessage("${f.path} is not world-writable").that(mode and 0b000_000_010).isEqualTo(0)
        }
        // Another uid (the shell) can't list or read it.
        assertThat(rule.shell("ls ${data.path}/files")).doesNotContain("datastore")
        assertThat(rule.shell("cat ${data.path}/files/datastore/guardian_state.json")).doesNotContain("onboardingDone")
        // Nothing on shared storage.
        assertThat(rule.shell("find /sdcard/Android/data/${rule.pkg} /sdcard/Android/media/${rule.pkg} -type f").trim()).isEmpty()
    }

    /**
     * UiAutomation runs shell commands without a shell and returns only stdout, while `am`
     * prints its "Permission Denial" to stderr; so each attempt is judged by its effect
     * (nothing of Guardian's shows up), plus the manifest flag Android enforces.
     */
    @Test fun gm_priv_010_privateScreensAndServiceCantBeStartedFromOutside() {
        val pm = rule.context.packageManager
        val activities = listOf(
            "app.guardianmode.ui.cards.GuardianCardActivity",
            "app.guardianmode.ui.onboarding.OnboardingActivity",
            "app.guardianmode.ui.help.HelpActivity",
        )
        for (cls in activities) {
            assertWithMessage("$cls exported").that(pm.getActivityInfo(ComponentName(rule.pkg, cls), 0).exported).isFalse()
        }
        val service = "app.guardianmode.protection.GuardianService"
        assertThat(pm.getServiceInfo(ComponentName(rule.pkg, service), 0).exported).isFalse()

        rule.open(StubApp.NOTES)
        assertThat(rule.waitForPackage(StubApp.NOTES.packageName)).isTrue()
        // A fake pause card, as another app would try (JSON without spaces: no shell quoting here).
        rule.shell("am start -n ${rule.pkg}/app.guardianmode.ui.cards.GuardianCardActivity --es request {\"kind\":\"PAUSE\",\"appLabel\":\"Fake\"}")
        assertThat(rule.staysAway(rule.pauseCard, 3_000)).isTrue()
        rule.shell("am start -n ${rule.pkg}/app.guardianmode.ui.help.HelpActivity")
        assertThat(rule.staysAway(By.text("Ask your family for help"), 3_000)).isTrue()
        rule.shell("am start -n ${rule.pkg}/app.guardianmode.ui.onboarding.OnboardingActivity")
        Thread.sleep(2_000)
        assertThat(rule.topPackage()).isEqualTo(StubApp.NOTES.packageName)
        Log.i(TAG, "GM-PRIV-010 am startservice: ${rule.shell("am startservice -n ${rule.pkg}/$service").trim()}")
    }

    @Test fun gm_priv_011_fakeBroadcastsAreRefused() {
        rule.shell("am broadcast -a app.guardianmode.PAUSE_HOUR -n ${rule.pkg}/app.guardianmode.protection.NotificationActionReceiver")
        Thread.sleep(3_000)
        assertWithMessage("a fake PAUSE_HOUR pauses protection").that(rule.state.isPaused(System.currentTimeMillis())).isFalse()

        val phone = rule.shell("am broadcast -a android.intent.action.PHONE_STATE --es state OFFHOOK -n ${rule.pkg}/app.guardianmode.protection.PhoneStateReceiver")
        Thread.sleep(2_000)
        Log.i(TAG, "GM-PRIV-011 fake PHONE_STATE: ${phone.trim()}")
        assertWithMessage("a fake OFFHOOK reaches the brain").that(rule.container.brain.callActive).isFalse()

        // BOOT_COMPLETED from the shell may get through; at most it starts the service (harmless).
        Log.i(TAG, "GM-PRIV-011 fake BOOT_COMPLETED: ${rule.shell("am broadcast -a android.intent.action.BOOT_COMPLETED -n ${rule.pkg}/app.guardianmode.protection.BootReceiver").trim()}")
    }

    @Test fun gm_priv_012_shareTargetWontTurnPrivateFilesIntoPhotos() {
        val stateFile = File(rule.context.filesDir, "datastore/guardian_state.json")
        rule.shell(
            "am start -a android.intent.action.SEND -t image/jpeg --eu android.intent.extra.STREAM file://${stateFile.path} " +
                "-n ${rule.pkg}/app.guardianmode.ui.home.SharePhotosActivity",
        )
        assertThat(rule.waitText("That didn't work", 10_000)).isTrue()
        assertThat(rule.state.localPhotos).isEmpty()
    }

    @Test fun gm_priv_012_hugeShareIsCappedAtThirtyPhotos() {
        val resolver = rule.context.contentResolver
        val uris = (1..35).map { i ->
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "gm-priv-012-$i.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GuardianTest")
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            resolver.openOutputStream(uri)!!.use { out ->
                Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(i * 7, 100, 200)) }
                    .compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            uri
        }
        try {
            val share = Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("image/*")
                .setClassName(rule.pkg, "app.guardianmode.ui.home.SharePhotosActivity")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            rule.context.startActivity(share)
            assertThat(rule.waitText("Added to your photo card", 30_000)).isTrue()
            assertThat(rule.state.localPhotos.size).isAtMost(30)
            assertThat(rule.seen(By.text("The 30 pictures are on your home screen now."))).isTrue()
        } finally {
            uris.forEach { runCatching { resolver.delete(it, null, null) } }
        }
    }

    /**
     * Guardian can't hide: it has its own launcher entry under its own name and icon, the
     * service runs in the foreground (so Android lists it under "Active apps" on API 33+ and
     * lets the parent stop it there), the notification is up and can't be swiped away, and it
     * offers a pause right there. "Stop using Guardian Mode" is on the transparency screen.
     * (Stopping from "Active apps" itself ends the app, so that part stays with GM-SVC-003.)
     */
    @Test fun gm_priv_022_gm_inst_001_visibleInLauncherAndShadeAndStoppable() {
        val pm = rule.context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(rule.pkg)
        val entries = pm.queryIntentActivities(launcher, 0)
        assertWithMessage("launcher entries").that(entries.map { it.activityInfo.name }).containsExactly("app.guardianmode.ui.MainActivity")
        val entry = entries.single()
        assertWithMessage("launcher label").that(entry.loadLabel(pm).toString()).isEqualTo("Guardian Mode")
        assertWithMessage("app label").that(pm.getApplicationLabel(pm.getApplicationInfo(rule.pkg, 0)).toString()).isEqualTo("Guardian Mode")
        assertWithMessage("launcher icon is Guardian's own").that(entry.activityInfo.applicationInfo.icon).isEqualTo(app.guardianmode.R.mipmap.ic_launcher)
        val main = ComponentName(rule.pkg, "app.guardianmode.ui.MainActivity")
        assertWithMessage("launcher entry disabled").that(pm.getComponentEnabledSetting(main))
            .isAnyOf(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        // What a launcher actually lists (an app with no launcher entry would get a synthetic one, or none).
        val apps = rule.context.getSystemService(LauncherApps::class.java).getActivityList(rule.pkg, Process.myUserHandle())
        assertWithMessage("LauncherApps entries").that(apps.map { it.componentName.className }).containsExactly("app.guardianmode.ui.MainActivity")
        assertWithMessage("LauncherApps label").that(apps.single().label.toString()).isEqualTo("Guardian Mode")

        // Running, in the foreground, with its notification up and a way to pause from it.
        assertThat(rule.waitUntil { rule.protectionNotification() != null }).isTrue()
        val sbn = rule.protectionNotification()!!
        assertThat(sbn.isOngoing).isTrue()
        assertThat(sbn.notification.flags and Notification.FLAG_FOREGROUND_SERVICE).isNotEqualTo(0)
        val nm = rule.context.getSystemService(NotificationManager::class.java)
        assertWithMessage("protection channel importance").that(nm.getNotificationChannel(sbn.notification.channelId)!!.importance)
            .isGreaterThan(NotificationManager.IMPORTANCE_NONE)
        assertThat(sbn.notification.actions.map { it.title.toString() }).contains("Pause for 1 hour")
        // Deprecated for other apps' services, but still returns the caller's own.
        @Suppress("DEPRECATION")
        val service = rule.context.getSystemService(ActivityManager::class.java).getRunningServices(50)
            .firstOrNull { it.service.className == "app.guardianmode.protection.GuardianService" }
        assertWithMessage("GuardianService is running").that(service).isNotNull()
        assertWithMessage("GuardianService runs in the foreground").that(service!!.foreground).isTrue()

        // And the way out is on the transparency screen.
        rule.openTransparency()
        assertThat(rule.seen(By.text("Stop using Guardian Mode"))).isTrue()
    }

    /** Everything under Guardian's data dir, without following links out of it (lib/ points at the APK's libraries). */
    private fun ownFiles(): List<File> {
        val base = rule.context.dataDir.canonicalPath
        return rule.context.dataDir.walkTopDown()
            .onEnter { it.canonicalPath.startsWith(base) }
            .filter { it.canonicalPath.startsWith(base) }
            .toList()
    }

    private companion object {
        const val TAG = "PrivacyDeviceTest"
        const val CONSOLE_HOST = "10.0.2.2"
        const val CONSOLE_PORT = 5554

        /** Reverse names of Google's servers, where Firebase's start-up calls go. */
        val GOOGLE = listOf(".1e100.net", ".google.com", ".googleapis.com", ".gstatic.com")
    }
}
