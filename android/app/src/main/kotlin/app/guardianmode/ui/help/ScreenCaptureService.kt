package app.guardianmode.ui.help

import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import app.guardianmode.R
import app.guardianmode.protection.Notifications
import java.io.File
import java.util.UUID

/**
 * Takes exactly one screenshot after the parent approved Android's own "start recording"
 * dialog, then stops. Android requires a media-projection foreground service for this.
 */
class ScreenCaptureService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_PROTECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Taking a picture of your screen")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching { capture(resultCode, data) }.onFailure {
            Log.e(TAG, "Screen capture failed", it)
            cleanup()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun capture(resultCode: Int, data: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp = manager.getMediaProjection(resultCode, data) ?: error("No projection")
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = cleanup()
        }, main)

        val (width, height) = screenSize()
        val dpi = resources.displayMetrics.densityDpi
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = imageReader
        display = mp.createVirtualDisplay(
            "guardian-help", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, main,
        )
        // Give the help screen time to slide away so the picture shows what confused the parent.
        main.postDelayed({ grab(imageReader, width, height) }, CAPTURE_DELAY)
    }

    private fun grab(imageReader: ImageReader, width: Int, height: Int) {
        val image = imageReader.acquireLatestImage()
        if (image == null) {
            main.postDelayed({ grab(imageReader, width, height) }, 200)
            return
        }
        val file = runCatching {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * width
            val padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
            val scale = (MAX_WIDTH.toFloat() / width).coerceAtMost(1f)
            val scaled = Bitmap.createScaledBitmap(cropped, (width * scale).toInt(), (height * scale).toInt(), true)
            val dir = File(cacheDir, "help").apply { mkdirs() }
            File(dir, "${UUID.randomUUID()}.jpg").also { out ->
                out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            }
        }.getOrNull()
        image.close()
        cleanup()
        file?.let(::showPreview)
        stopSelf()
    }

    private fun showPreview(file: File) {
        val intent = HelpActivity.previewIntent(this, file.absolutePath)
        val opened = Settings.canDrawOverlays(this) && runCatching { startActivity(intent) }.isSuccess
        if (!opened) {
            val pending = PendingIntent.getActivity(this, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(this, Notifications.CHANNEL_CARDS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Your picture is ready")
                .setContentText("Tap to look at it and send it to your family.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(Notifications.ID_HELP_READY, n)
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val m = resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    private fun cleanup() {
        display?.release()
        display = null
        reader?.close()
        reader = null
        val p = projection
        projection = null
        p?.stop()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenCapture"
        private const val NOTIFICATION_ID = 4
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"
        private const val CAPTURE_DELAY = 900L
        private const val MAX_WIDTH = 1080

        fun start(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, ScreenCaptureService::class.java)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_DATA, data),
            )
        }
    }
}
