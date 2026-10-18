package app.guardianmode.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Opening other apps and placing calls, with graceful fallbacks for unusual phones. */
object Launch {

    /** Calls straight away when allowed, otherwise opens the dialer with the number filled in. */
    fun call(context: Context, number: String) {
        if (number.isBlank()) return
        val uri = Uri.fromParts("tel", number, null)
        val canCall = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri)
        start(context, intent) || start(context, Intent(Intent.ACTION_DIAL, uri))
    }

    fun dialer(context: Context) = start(context, Intent(Intent.ACTION_DIAL))

    fun messages(context: Context): Boolean {
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)
        return start(context, main) || start(context, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")))
    }

    fun camera(context: Context) = start(context, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))

    fun app(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return start(context, intent)
    }

    fun phoneSettings(context: Context) = start(context, Intent(Settings.ACTION_SETTINGS))

    fun homeSettings(context: Context) = start(context, Intent(Settings.ACTION_HOME_SETTINGS)) ||
        start(context, Intent(Settings.ACTION_SETTINGS))

    /** Opens Android's own uninstall confirmation, falling back to the app's info page. */
    fun uninstall(context: Context, packageName: String) {
        val uri = Uri.fromParts("package", packageName, null)
        @Suppress("DEPRECATION")
        start(context, Intent(Intent.ACTION_DELETE, uri)) ||
            start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
    }

    fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
