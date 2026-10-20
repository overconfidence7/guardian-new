package app.guardianmode.device

import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import android.system.Os
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
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
            assertThat(rule.has(By.text("The 30 pictures are on your home screen now."))).isTrue()
        } finally {
            uris.forEach { runCatching { resolver.delete(it, null, null) } }
        }
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
    }
}
