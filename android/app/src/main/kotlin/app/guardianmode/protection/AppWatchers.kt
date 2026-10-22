package app.guardianmode.protection

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Reads which app is in front from Usage access. Never reads anything inside the app. */
class ForegroundWatcher(private val context: Context) {
    private val usage = context.getSystemService(UsageStatsManager::class.java)
    private var lastQueryEnd = System.currentTimeMillis() - LOOKBACK
    private var lastReported: Pair<Long, Foreground>? = null

    /**
     * The screen most recently brought to the front, if it changed since the last poll.
     *
     * Android stamps a switch with when it happened but can store it a moment later, so a
     * switch stamped before the last poll may only show up now. Each poll looks back
     * [LOOKBACK] past the last one and reports the newest switch it hasn't reported yet.
     */
    fun poll(now: Long = System.currentTimeMillis()): Foreground? {
        val events = runCatching { usage.queryEvents(lastQueryEnd - LOOKBACK, now) }.getOrNull() ?: return null
        lastQueryEnd = now
        val event = UsageEvents.Event()
        var latest: Pair<Long, Foreground>? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            if (latest == null || event.timeStamp >= latest.first) {
                latest = event.timeStamp to Foreground(event.packageName, event.className.orEmpty())
            }
        }
        val newest = latest ?: return null
        val reported = lastReported
        // Already reported, or older than what was: a newer switch is what's in front.
        if (reported != null && (newest == reported || newest.first < reported.first)) return null
        lastReported = newest
        return newest.second
    }

    private companion object {
        const val LOOKBACK = 10_000L
    }
}

/** An app in front, and which of its screens (the lock needs it to tell a permission prompt from the permission manager). */
data class Foreground(val packageName: String, val className: String)

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
