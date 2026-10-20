package app.guardianmode.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Copies pictures shared from another app (Google Photos, Amazon Photos, the gallery)
 * or picked on this phone into Guardian's own storage, so the photo card keeps working
 * after the other app's permission to read them runs out. Nothing is uploaded.
 */
class PhotoImporter(private val context: Context, private val store: LocalStore, private val clock: () -> Long = System::currentTimeMillis) {
    private val dir get() = File(context.filesDir, "photos").apply { mkdirs() }

    /** Returns how many pictures were added. */
    suspend fun import(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        val added = uris.take(LocalState.MAX_LOCAL_PHOTOS).mapIndexedNotNull { i, uri ->
            runCatching { copy(uri, clock() + i) }.onFailure { Log.w(TAG, "Couldn't add a photo", it) }.getOrNull()
        }
        if (added.isEmpty()) return@withContext 0
        val updated = store.update { s -> s.copy(localPhotos = (added + s.localPhotos).take(LocalState.MAX_LOCAL_PHOTOS)) }
        cleanUp(updated.localPhotos)
        added.size
    }

    suspend fun remove(id: String) {
        val updated = store.update { s -> s.copy(localPhotos = s.localPhotos.filterNot { it.id == id }) }
        withContext(Dispatchers.IO) { cleanUp(updated.localPhotos) }
    }

    private fun copy(uri: Uri, at: Long): PhotoItem {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val scale = minOf(1f, MAX_SIDE.toFloat() / max(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val id = "$LOCAL_PREFIX${UUID.randomUUID()}"
        val file = File(dir, "$id.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        return PhotoItem(id = id, url = Uri.fromFile(file).toString(), fromName = "", createdAt = at)
    }

    private fun cleanUp(kept: List<PhotoItem>) {
        val keep = kept.map { "${it.id}.jpg" }.toSet()
        dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }

    companion object {
        const val LOCAL_PREFIX = "local-"
        private const val MAX_SIDE = 1600
        private const val TAG = "PhotoImporter"

        fun isLocal(photo: PhotoItem) = photo.id.startsWith(LOCAL_PREFIX)
    }
}
