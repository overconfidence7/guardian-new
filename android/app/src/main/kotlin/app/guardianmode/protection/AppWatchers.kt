package app.guardianmode.protection

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Reads which app is in front from Usage access. Never reads anything inside the app. */
class ForegroundWatcher(private val context: Context) {
    private val usage = context.getSystemService(UsageStatsManager::class.java)
    private var lastQueryEnd = System.currentTimeMillis() - 10_000L

    /** The package most recently brought to the front since the last poll, if any. */
    fun poll(now: Long = System.currentTimeMillis()): String? {
        val events = runCatching { usage.queryEvents(lastQueryEnd, now) }.getOrNull() ?: return null
        lastQueryEnd = now
        val event = UsageEvents.Event()
        var latest: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) latest = event.packageName
        }
        return latest
    }
}

data class InstalledApp(val packageName: String, val label: String, val controlCapable: Boolean)

/** Finds apps installed since the last check, using Android's package change sequence numbers. */
class InstallWatcher(private val context: Context) {
    private val pm = context.packageManager

    /** Returns new installs and the sequence number to store for next time. */
    fun newInstalls(lastSequence: Int, since: Long): Pair<List<InstalledApp>, Int> {
        val changed = pm.getChangedPackages(lastSequence) ?: return emptyList<InstalledApp>() to lastSequence
        val installs = changed.packageNames.mapNotNull { pkg ->
            val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
            val fresh = info.firstInstallTime == info.lastUpdateTime && info.firstInstallTime >= since
            if (!fresh || pkg == context.packageName) return@mapNotNull null
            InstalledApp(pkg, label(pkg), hasAccessibilityService(pkg))
        }
        return installs to changed.sequenceNumber
    }

    fun currentSequence(): Int = pm.getChangedPackages(0)?.sequenceNumber ?: 0

    fun label(pkg: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun hasAccessibilityService(pkg: String): Boolean =
        pm.queryIntentServices(
            Intent("android.accessibilityservice.AccessibilityService").setPackage(pkg),
            PackageManager.GET_META_DATA,
        ).isNotEmpty()

    /** Launchable apps, reported to the dashboard so helpers can pick tiles and allowed apps. */
    fun launchableApps(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { it.packageName to pm.getApplicationLabel(it).toString() }
            .sortedBy { it.second.lowercase() }
    }
}
