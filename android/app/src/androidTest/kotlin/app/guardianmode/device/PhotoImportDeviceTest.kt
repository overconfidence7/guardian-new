package app.guardianmode.device

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.guardianmode.data.LocalState
import app.guardianmode.data.PhotoImporter
import app.guardianmode.support.DeviceRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * qa/antigravity/cases/05-photos.md: what Guardian keeps of the pictures handed to it (by
 * the share sheet or the photo picker), with the phone's own image decoder. These call the
 * importer the share page uses (`container.photos`) directly, with no screens, so they are
 * quick; the share page itself is gm_photo_003 in PhotoDeviceTest. Robolectric's decoder
 * can't read a content or file URI, so this can't be a JVM test.
 */
@RunWith(AndroidJUnit4::class)
class PhotoImportDeviceTest {
    @get:Rule val rule = DeviceRule()

    private val context get() = rule.context
    private val photosDir get() = File(context.filesDir, "photos")
    private val sourceDir get() = File(context.cacheDir, "gm-photo-sources").apply { mkdirs() }

    @Before fun noPhotos() {
        rule.updateState { it.copy(localPhotos = emptyList()) }
        photosDir.deleteRecursively()
    }

    @After fun cleanUp() {
        rule.updateState { it.copy(localPhotos = emptyList()) }
        photosDir.deleteRecursively()
        sourceDir.deleteRecursively()
    }

    /** A plain picture in Guardian's cache; RGB_565 keeps a big one small in memory on the budget phone. */
    private fun picture(name: String, w: Int, h: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG, color: Int = Color.rgb(200, 120, 40)): Uri {
        val file = File(sourceDir, name)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565).apply { eraseColor(color) }
        file.outputStream().use { bitmap.compress(format, 90, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    private fun import(vararg uris: Uri): Int = runBlocking { rule.container.photos.import(uris.toList()) }

    private val photos get() = rule.state.localPhotos

    private fun fileOf(url: String) = File(Uri.parse(url).path!!)

    private fun size(file: File) = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        .also { BitmapFactory.decodeFile(file.path, it) }.let { it.outWidth to it.outHeight }

    private fun isJpeg(file: File) = file.inputStream().use {
        val b = ByteArray(3)
        it.read(b) == 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()
    }

    private fun savedFiles() = photosDir.listFiles()?.map { it.name }?.toSet().orEmpty()

    @Test fun gm_photo_005_resizedToJpegAt1600AndKeptPrivately() {
        assertThat(import(picture("big.jpg", 4000, 3000))).isEqualTo(1)
        val photo = photos.single()
        val file = fileOf(photo.url)
        assertWithMessage("kept in Guardian's own files/photos").that(file.parentFile!!.canonicalPath).isEqualTo(photosDir.canonicalPath)
        assertThat(savedFiles()).containsExactly("${photo.id}.jpg")
        assertWithMessage("saved as JPEG").that(isJpeg(file)).isTrue()
        assertWithMessage("longest side 1600 px, same shape").that(size(file)).isEqualTo(1600 to 1200)
        assertWithMessage("a few hundred KB at most").that(file.length()).isLessThan(1_000_000L)
        assertWithMessage("a picture added on the phone is a local one").that(PhotoImporter.isLocal(photo)).isTrue()
        // A tall picture is limited by its height, and a small one isn't blown up.
        assertThat(import(picture("tall.jpg", 1500, 4500))).isEqualTo(1)
        assertThat(import(picture("small.jpg", 320, 240))).isEqualTo(1)
        assertThat(photos.map { size(fileOf(it.url)) }).containsExactly(320 to 240, 533 to 1600, 1600 to 1200).inOrder()
        // Nothing lands on shared storage: every saved picture is under Guardian's private files.
        assertThat(photos.map { fileOf(it.url).canonicalPath }.all { it.startsWith(context.filesDir.canonicalPath) }).isTrue()
    }

    @Test fun gm_photo_006_oddFilesWorkOrFailWithoutCrashing() {
        val corrupt = File(sourceDir, "corrupt.jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(5000) { (it * 31).toByte() })
        }
        val notAPicture = File(sourceDir, "notes.jpg").apply { writeText("This isn't a picture.") }
        val gone = Uri.fromFile(File(sourceDir, "gone.jpg"))

        for ((label, uri) in listOf(
            "a PNG" to picture("pic.png", 800, 600, Bitmap.CompressFormat.PNG),
            "a WebP" to picture("pic.webp", 800, 600, @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP),
            "a 9000x1500 panorama" to picture("panorama.jpg", 9000, 1500),
        )) {
            val started = System.currentTimeMillis()
            assertWithMessage("$label is added").that(import(uri)).isEqualTo(1)
            assertWithMessage("$label took under 10 s").that(System.currentTimeMillis() - started).isLessThan(10_000L)
            val file = fileOf(photos.first().url)
            assertWithMessage("$label is saved as JPEG").that(isJpeg(file)).isTrue()
            assertWithMessage("$label's longest side").that(size(file).let { maxOf(it.first, it.second) }).isAtMost(1600)
        }
        val before = photos
        for ((label, uri) in listOf("a corrupt .jpg" to Uri.fromFile(corrupt), "a text file named .jpg" to Uri.fromFile(notAPicture), "a file that's gone" to gone)) {
            // 0 added is what makes the share page say "That didn't work".
            assertWithMessage("$label adds nothing").that(import(uri)).isEqualTo(0)
        }
        assertThat(photos).isEqualTo(before)
        assertWithMessage("no half-written files left").that(savedFiles()).isEqualTo(before.map { "${it.id}.jpg" }.toSet())
        // A batch with bad files in it keeps the good one.
        assertThat(import(Uri.fromFile(corrupt), picture("good.jpg", 640, 480), gone)).isEqualTo(1)
        assertThat(photos).hasSize(before.size + 1)
    }

    @Test fun gm_photo_007_keepsTheNewest30AndDeletesTheRest() {
        val batches = mutableListOf<List<String>>()
        // 35 pictures in batches of 5.
        for (batch in 0 until 7) {
            val uris = (0 until 5).map { picture("p$batch-$it.jpg", 160, 120, color = Color.rgb(batch * 30, it * 50, 100)) }
            assertThat(import(*uris.toTypedArray())).isEqualTo(5)
            batches += photos.take(5).map { it.id }
            Thread.sleep(5) // each batch strictly newer than the last
        }
        val kept = photos
        assertThat(kept).hasSize(LocalState.MAX_LOCAL_PHOTOS)
        assertWithMessage("newest first").that(kept.map { it.createdAt }).isInOrder(Comparator.reverseOrder<Long>())
        assertWithMessage("the 30 newest are kept").that(kept.map { it.id }.toSet()).isEqualTo(batches.drop(1).flatten().toSet())
        assertWithMessage("the oldest 5 files are deleted and the 30 kept are there").that(savedFiles()).isEqualTo(kept.map { "${it.id}.jpg" }.toSet())
        // One share bigger than the limit keeps 30 too.
        assertThat(import(*(0 until 35).map { picture("q$it.jpg", 160, 120) }.toTypedArray())).isEqualTo(LocalState.MAX_LOCAL_PHOTOS)
        assertThat(photos).hasSize(LocalState.MAX_LOCAL_PHOTOS)
        assertThat(savedFiles()).hasSize(LocalState.MAX_LOCAL_PHOTOS)
    }
}
