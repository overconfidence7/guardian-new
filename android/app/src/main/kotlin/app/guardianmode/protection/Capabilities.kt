package app.guardianmode.protection

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat

/** One thing Android must allow before a Guardian feature can work. */
enum class Capability(val key: String, val plainName: String) {
    HOME_APP("home", "Guardian is the home screen"),
    USAGE_ACCESS("usage", "Can tell which app is open"),
    OVERLAY("overlay", "Can show pause cards over other apps"),
    CALL_SCREENING("screening", "Can screen unknown callers"),
    PHONE_STATE("phoneState", "Can tell when a call starts and ends"),
    CONTACTS("contacts", "Can recognise your contacts"),
    CALL_PHONE("callPhone", "Can call family with one tap"),
    NOTIFICATIONS("notifications", "Can show reminders and its notification"),
    EXACT_ALARMS("exactAlarms", "Reminders arrive on time"),
    FULL_SCREEN("fullScreen", "Reminders can fill the screen"),
    BATTERY("battery", "Android won't put Guardian to sleep"),
}

/** Checks and settings deep links for every capability. All checks are cheap and side-effect free. */
class Capabilities(private val context: Context) {

    fun has(c: Capability): Boolean = when (c) {
        Capability.HOME_APP -> holdsRole(RoleManager.ROLE_HOME)
        Capability.USAGE_ACCESS -> usageAccessGranted()
        Capability.OVERLAY -> Settings.canDrawOverlays(context)
        Capability.CALL_SCREENING -> holdsRole(RoleManager.ROLE_CALL_SCREENING)
        Capability.PHONE_STATE -> granted(Manifest.permission.READ_PHONE_STATE)
        Capability.CONTACTS -> granted(Manifest.permission.READ_CONTACTS)
        Capability.CALL_PHONE -> granted(Manifest.permission.CALL_PHONE)
        Capability.NOTIFICATIONS -> Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)
        Capability.EXACT_ALARMS -> Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        Capability.FULL_SCREEN -> Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        Capability.BATTERY -> context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }

    fun missing(of: Collection<Capability> = Capability.entries): List<Capability> = of.filterNot(::has)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun holdsRole(role: String): Boolean {
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleAvailable(role) && rm.isRoleHeld(role)
    }

    private fun usageAccessGranted(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    fun roleRequestIntent(role: String): Intent? {
        val rm = context.getSystemService(RoleManager::class.java) ?: return null
        return if (rm.isRoleAvailable(role)) rm.createRequestRoleIntent(role) else null
    }

    /** The Settings screen that grants a special-access capability. Runtime permissions are requested separately. */
    fun settingsIntent(c: Capability): Intent? {
        val pkg = Uri.fromParts("package", context.packageName, null)
        return when (c) {
            Capability.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                // Opens straight to Guardian's switch on Android 10+ builds that support it.
                data = pkg
            }
            Capability.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            Capability.EXACT_ALARMS -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg) else null
            Capability.FULL_SCREEN -> if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg) else null
            Capability.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            Capability.HOME_APP -> roleRequestIntent(RoleManager.ROLE_HOME) ?: Intent(Settings.ACTION_HOME_SETTINGS)
            Capability.CALL_SCREENING -> roleRequestIntent(RoleManager.ROLE_CALL_SCREENING)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Fallback when a direct deep link is rejected by an OEM build. */
    fun fallbackSettingsIntent(c: Capability): Intent = when (c) {
        Capability.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        /** Which capabilities each consent screen asks for, keyed by feature. */
        val BY_FEATURE: Map<String, List<Capability>> = mapOf(
            Features.HOME to listOf(Capability.HOME_APP, Capability.CALL_PHONE),
            Features.PAUSE to listOf(Capability.USAGE_ACCESS, Capability.OVERLAY),
            Features.CALLS to listOf(Capability.CALL_SCREENING, Capability.PHONE_STATE, Capability.CONTACTS),
            Features.REMINDERS to listOf(Capability.NOTIFICATIONS, Capability.EXACT_ALARMS, Capability.FULL_SCREEN),
            Features.BATTERY to listOf(Capability.BATTERY),
        )
    }
}

/** Consent feature keys, shared by onboarding, transparency and the status report. */
object Features {
    const val HOME = "home"
    const val PAUSE = "pause"
    const val CALLS = "calls"
    const val CHECK_IN = "checkIn"
    const val REMINDERS = "reminders"
    const val BATTERY = "battery"
    const val CONSENT_VERSION = 1
}
