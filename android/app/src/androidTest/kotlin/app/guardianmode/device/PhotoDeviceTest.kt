package app.guardianmode.device

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import app.guardianmode.support.DeviceRule
import app.guardianmode.support.StubApp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.regex.Pattern
import kotlin.math.abs

/**
 * qa/antigravity/cases/05-photos.md on a device: a picture shared from another app the way
 * a gallery's share sheet sends it (ACTION_SEND with a MediaStore content URI and a read
 * grant), with the other app in front.
 *
 * GM-PHOTO-002 opens Android's real photo picker from "Add photos from this phone", then hands
 * Guardian three pictures the way the picker returns them (picking inside the picker is
 * Android's own UI, different on every level, so the pick itself is stood in for).
 *
 * Not here: GM-PHOTO-009 (force stop, reinstall and reboot end this process; the host-side
 * script does it, gm_photo_009_photosSurviveRestartUpdateAndReboot).
 */
@RunWith(AndroidJUnit4::class)
class PhotoDeviceTest {
    @get:Rule val rule = DeviceRule()

    private val inserted = mutableListOf<Uri>()

    /** The photo card on the home screen: its spoken label starts with the day it was added. */
    private val photoCard = By.desc(Pattern.compile("(Today|Yesterday)\\..*", Pattern.DOTALL))

    @After fun deletePictures() {
        monitors.forEach { rule.instrumentation.removeMonitor(it) }
        inserted.forEach { runCatching { rule.context.contentResolver.delete(it, null, null) } }
    }

    private val monitors = mutableListOf<Instrumentation.ActivityMonitor>()

    /** Watches what Guardian starts; when [result] is given, picker intents get it instead of opening. */
    private fun watchPicker(result: Intent? = null): MutableList<Intent> {
        val seen = Collections.synchronizedList(mutableListOf<Intent>())
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                seen += Intent(intent)
                return if (result != null && intent.action in PICKER_ACTIONS) Instrumentation.ActivityResult(Activity.RESULT_OK, result) else null
            }
        }
        rule.instrumentation.addMonitor(monitor)
        monitors += monitor
        return seen
    }

    private fun stopWatching() {
        monitors.forEach { rule.instrumentation.removeMonitor(it) }
        monitors.clear()
    }

    /** The colour at the middle of a saved photo file. */
    private fun colorOf(photo: app.guardianmode.data.PhotoItem): Int {
        val bitmap = BitmapFactory.decodeFile(Uri.parse(photo.url).path)
        return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2).also { bitmap.recycle() }
    }

    private fun near(actual: Int, expected: Int) =
        listOf(Color::red, Color::green, Color::blue).all { abs(it(actual) - it(expected)) <= 40 }

    private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    private fun picture(name: String, color: Int): Uri {
        val resolver = rule.context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GuardianTest")
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        inserted += uri
        resolver.openOutputStream(uri)!!.use { out ->
            Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        return uri
    }

    /** Shares [uri] to "Add to photo card" while [from] is the app in front. */
    private fun share(uri: Uri, from: StubApp) {
        rule.open(from)
        assertWithMessage("${from.label} opened").that(rule.waitForPackage(from.packageName)).isTrue()
        rule.context.startActivity(
            Intent(Intent.ACTION_SEND)
                .setType("image/jpeg")
                .setClassName(rule.pkg, "app.guardianmode.ui.home.SharePhotosActivity")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    @Test fun gm_photo_003_shareOnePictureFromAnotherApp() {
        rule.requireStub(StubApp.NOTES)
        // The share sheet's entry: Guardian's own name for it, for any picture.
        val pm = rule.context.packageManager
        val targets = pm.queryIntentActivities(Intent(Intent.ACTION_SEND).setType("image/jpeg").setPackage(rule.pkg), 0)
        assertWithMessage("share targets").that(targets.map { it.activityInfo.name }).containsExactly("app.guardianmode.ui.home.SharePhotosActivity")
        val target = targets.single()
        assertWithMessage("share sheet label").that(target.loadLabel(pm).toString()).isEqualTo("Add to photo card")
        Log.i(TAG, "GM-PHOTO-003 share sheet: label \"${target.loadLabel(pm)}\", icon res ${target.iconResource} (app icon ${target.activityInfo.applicationInfo.icon})")
        assertWithMessage("share multiple too").that(
            pm.queryIntentActivities(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").setPackage(rule.pkg), 0).map { it.activityInfo.name },
        ).containsExactly("app.guardianmode.ui.home.SharePhotosActivity")

        rule.goHome()
        assertThat(rule.seen(By.text("Welcome to your new home screen."))).isTrue()
        assertThat(rule.state.localPhotos).isEmpty()

        // First share, then "Back to the other app".
        share(picture("gm-photo-003-a", Color.rgb(200, 120, 40)), StubApp.NOTES)
        assertThat(rule.waitText("Added to your photo card", 30_000)).isTrue()
        assertThat(rule.has("The picture is on your home screen now.")).isTrue()
        assertThat(rule.has("Go to my home screen")).isTrue()
        assertThat(rule.state.localPhotos).hasSize(1)
        val first = rule.state.localPhotos.single()
        rule.tap("Back to the other app")
        assertWithMessage("\"Back to the other app\" goes back to Notes").that(rule.waitForPackage(StubApp.NOTES.packageName)).isTrue()

        // Second share, then "Go to my home screen": the newest picture is on the photo card.
        share(picture("gm-photo-003-b", Color.rgb(40, 120, 200)), StubApp.NOTES)
        assertThat(rule.waitText("Added to your photo card", 30_000)).isTrue()
        rule.tap("Go to my home screen")
        assertWithMessage("home screen shows the photo card").that(rule.seen(photoCard, 10_000)).isTrue()
        assertThat(rule.topPackage()).isEqualTo(rule.pkg)
        assertThat(rule.has("Welcome to your new home screen.")).isFalse()
        val photos = rule.state.localPhotos
        assertThat(photos).hasSize(2)
        assertWithMessage("newest first").that(photos.first().createdAt).isGreaterThan(first.createdAt)
        // Copied into Guardian's own storage, so it keeps showing after the other app's grant ends.
        for (p in photos) {
            val file = java.io.File(Uri.parse(p.url).path!!)
            assertWithMessage("${p.url} is in Guardian's files").that(file.canonicalPath).startsWith(rule.context.filesDir.canonicalPath)
            assertWithMessage("${p.url} exists").that(file.length()).isGreaterThan(0L)
        }
    }

    @Test fun gm_photo_002_addFromThePickerWithoutAPermission() {
        rule.goHome()
        rule.scrollAndTap(By.desc("Photos"))
        assertThat(rule.waitText("Family photos")).isTrue()
        assertThat(rule.state.localPhotos).isEmpty()
        val start = System.currentTimeMillis()

        // The real picker: Android's, opened with no permission asked first.
        val opened = watchPicker()
        rule.tap("Add photos from this phone")
        assertWithMessage("a picker opens").that(rule.waitUntil(15_000) { rule.topPackage() != rule.pkg }).isTrue()
        rule.waitUntil(5_000) { opened.isNotEmpty() }
        val picker = rule.topPackage()
        Log.i(TAG, "GM-PHOTO-002 picker: ${opened.map { it.action }} in $picker; on screen: ${rule.screenSummary().take(400)}")
        assertWithMessage("Guardian asks Android for its photo picker").that(opened.map { it.action }).containsAnyIn(PICKER_ACTIONS)
        assertWithMessage("the picker is Android's, not a permission dialog").that(picker).doesNotContain("permissioncontroller")
        repeat(3) { if (rule.topPackage() != rule.pkg) rule.back() }
        assertWithMessage("backing out returns to Family photos").that(rule.waitText("Family photos")).isTrue()
        assertWithMessage("nothing added by backing out").that(rule.state.localPhotos).isEmpty()
        stopWatching()

        // Three pictures picked, handed back the way the picker hands them back.
        val colors = listOf(Color.rgb(220, 40, 40), Color.rgb(40, 180, 60), Color.rgb(40, 80, 220))
        val uris = colors.mapIndexed { i, c -> picture("gm-photo-002-$i", c) }
        val picked = Intent().apply {
            clipData = ClipData.newRawUri("photos", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        watchPicker(result = picked)
        rule.tap("Add photos from this phone")
        assertWithMessage("all three are added").that(rule.waitUntil(30_000) { rule.state.localPhotos.size == 3 }).isTrue()
        stopWatching()
        val photos = rule.state.localPhotos
        assertWithMessage("newest first: the last picked, then the others").that(photos.map { it.createdAt }).isInOrder(Comparator.reverseOrder<Long>())
        val saved = photos.map { colorOf(it) }
        assertWithMessage("saved colours, newest first: ${saved.map(::hex)}").that(saved.zip(colors.reversed()).all { (a, e) -> near(a, e) }).isTrue()
        assertWithMessage("\"Family photos\" shows them").that(rule.waitFor(photoCard, 10_000)).isTrue()
        Log.i(TAG, "GM-PHOTO-002 photos on the page in view: ${rule.device.findObjects(photoCard).size}")

        // The home screen's photo card shows the newest.
        rule.goHome()
        val card = rule.find(photoCard, 10_000).visibleBounds
        val shot = rule.instrumentation.uiAutomation.takeScreenshot()
        val readable = if (shot.config == Bitmap.Config.HARDWARE) shot.copy(Bitmap.Config.ARGB_8888, false) else shot
        val onCard = readable.getPixel(card.centerX(), card.top + card.height() / 3)
        readable.recycle()
        shot.recycle()
        assertWithMessage("photo card colour ${hex(onCard)}, newest picked ${hex(colors.last())}").that(near(onCard, colors.last())).isTrue()

        // No permission was ever asked for, and none to read photos is even requested.
        val resumed = rule.resumesSince(start).map { "${it.first}/${it.second}" }
        assertWithMessage("no permission dialog: $resumed").that(resumed.none { "permissioncontroller" in it || "GrantPermissions" in it }).isTrue()
        val requested = rule.context.packageManager.getPackageInfo(rule.pkg, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()
        assertThat(requested).containsNoneOf(
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.ACCESS_MEDIA_LOCATION",
        )
    }

    private companion object {
        const val TAG = "PhotoDeviceTest"

        /** Android's photo picker (API 33+, or from Google Play services), or the documents picker it falls back to. */
        val PICKER_ACTIONS = setOf(
            "android.provider.action.PICK_IMAGES",
            "com.google.android.gms.provider.action.PICK_IMAGES",
            Intent.ACTION_OPEN_DOCUMENT,
            Intent.ACTION_GET_CONTENT,
        )
    }
}
