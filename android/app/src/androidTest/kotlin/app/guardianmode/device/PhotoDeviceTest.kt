package app.guardianmode.device

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
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
import java.util.regex.Pattern

/**
 * qa/antigravity/cases/05-photos.md on a device: a picture shared from another app the way
 * a gallery's share sheet sends it (ACTION_SEND with a MediaStore content URI and a read
 * grant), with the other app in front.
 *
 * Not here: GM-PHOTO-002 (the system photo picker is Android's own UI and differs per
 * level) and GM-PHOTO-009 (force stop, reinstall and reboot end this process; the
 * host-side script does it, gm_photo_009_photosSurviveRestartUpdateAndReboot).
 */
@RunWith(AndroidJUnit4::class)
class PhotoDeviceTest {
    @get:Rule val rule = DeviceRule()

    private val inserted = mutableListOf<Uri>()

    /** The photo card on the home screen: its spoken label starts with the day it was added. */
    private val photoCard = By.desc(Pattern.compile("(Today|Yesterday)\\..*", Pattern.DOTALL))

    @After fun deletePictures() {
        inserted.forEach { runCatching { rule.context.contentResolver.delete(it, null, null) } }
    }

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

    private companion object {
        const val TAG = "PhotoDeviceTest"
    }
}
