package app.guardianmode.protection

import android.content.Context
import android.app.NotificationManager
import app.guardianmode.cloud.CloudSync
import app.guardianmode.cloud.DeviceStatus
import app.guardianmode.core.DeviceConfig
import app.guardianmode.core.LockLevel
import app.guardianmode.core.ScreeningMode
import app.guardianmode.data.LocalState
import app.guardianmode.data.LocalStore

/** Parent-facing protection switches and the housekeeping that keeps helpers informed. */
class ProtectionController(
    private val context: Context,
    private val store: LocalStore,
    private val cloud: CloudSync,
    private val capabilities: Capabilities,
    private val installs: InstallWatcher,
    private val brain: GuardianBrain,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun pauseFor(millis: Long) {
        refreshNotification(store.update { it.copy(pausedUntil = clock() + millis) })
    }

    suspend fun resume() {
        refreshNotification(store.update { it.copy(pausedUntil = 0) })
    }

    // Posts from the state just written, not LocalStore.current, which catches up a moment later.
    private fun refreshNotification(state: LocalState) {
        if (!state.onboardingDone) return
        context.getSystemService(NotificationManager::class.java)
            .notify(Notifications.ID_PROTECTION, Notifications.protection(context, state.isPaused(clock())))
    }

    /**
     * The parent switched a feature on or off on their own phone. The change is written
     * to the shared config so helpers see it; the server tells them when something was
     * turned off. Nothing is hidden from either side.
     */
    suspend fun setFeature(key: String, enabled: Boolean) {
        val updated = store.update { s -> s.copy(config = s.config.withFeature(key, enabled)) }
        cloud.pushConfigFromPhone(updated.config)
    }

    /** The parent (with the code) changed how locked down the phone is. Helpers see it like any change. */
    suspend fun setLockLevel(level: LockLevel) {
        val updated = store.update { s -> s.copy(config = s.config.copy(lock = s.config.lock.copy(level = level))) }
        cloud.pushConfigFromPhone(updated.config)
    }

    /** Called when the phone is unlocked or the home screen is shown: the passive "I'm OK". */
    suspend fun onUserActive() {
        // The "I'm OK" is only sent when the parent said yes to it and it's still switched on.
        val state = store.current
        if (state.consent[Features.CHECK_IN] != true || !state.config.checkIn.enabled) return
        val now = clock()
        if (now - store.current.lastHeartbeatAt < HEARTBEAT_INTERVAL) return
        store.update { it.copy(lastHeartbeatAt = now) }
        cloud.heartbeat()
    }

    suspend fun checkInstalls() {
        val state = store.current
        val since = if (state.lastInstallCheckAt == 0L) clock() else state.lastInstallCheckAt - 60_000L
        if (state.packageSequence == 0) {
            // First run: remember where we are so existing apps never alarm.
            store.update { it.copy(packageSequence = installs.currentSequence(), lastInstallCheckAt = clock()) }
            return
        }
        val (fresh, sequence) = installs.newInstalls(state.packageSequence, since)
        store.update { it.copy(packageSequence = sequence, lastInstallCheckAt = clock()) }
        fresh.forEach { brain.onInstalled(it.packageName, it.label, it.controlCapable) }
    }

    suspend fun reportStatus() {
        val consent = store.current.consent
        val missing = missingCapabilities()
        cloud.reportStatus(
            DeviceStatus(
                protected = !needsAttention() && !store.current.isPaused(clock()),
                missing = missing.map { it.key },
                // The app list is only for helpers setting up home buttons, which the parent may have said no to.
                installedApps = if (consent[Features.HOME] == true) {
                    runCatching { installs.launchableApps() }.getOrDefault(emptyList())
                } else {
                    emptyList()
                },
            ),
        )
    }

    private fun missingCapabilities(): List<Capability> {
        val consent = store.current.consent
        val needed = Capabilities.BY_FEATURE.filterKeys { consent[it] == true }.values.flatten().distinct()
        return capabilities.missing(needed)
    }

    /**
     * True when the shield shouldn't say "Protected": a permission is missing for something
     * the parent turned on, or they turned on neither of the protections that slow a scam down.
     */
    fun needsAttention(): Boolean {
        val consent = store.current.consent
        val noProtection = consent[Features.PAUSE] != true && consent[Features.CALLS] != true
        // At Locked, covering an app needs one of these; without either the cover is only a notification.
        val state = store.current
        val lockCantCover = state.config.lock.level == LockLevel.LOCKED && state.lockCodeHash.isNotEmpty() &&
            !capabilities.has(Capability.OVERLAY) && !capabilities.has(Capability.HOME_APP)
        return noProtection || lockCantCover || missingCapabilities().isNotEmpty()
    }

    companion object {
        const val HEARTBEAT_INTERVAL = 15 * 60_000L
    }
}

/** Applies a parent's on/off switch from the transparency screen to the config. */
fun DeviceConfig.withFeature(key: String, enabled: Boolean): DeviceConfig {
    val p = protection
    return when (key) {
        "pause" -> copy(protection = p.copy(pauseTripwires = p.pauseTripwires.copy(enabled = enabled)))
        "remoteAccess" -> copy(protection = p.copy(remoteAccessAlarm = p.remoteAccessAlarm.copy(enabled = enabled)))
        "longCall" -> copy(protection = p.copy(longCallAlert = p.longCallAlert.copy(enabled = enabled)))
        "screening" -> copy(
            protection = p.copy(
                callScreening = p.callScreening.copy(mode = if (enabled) ScreeningMode.SILENCE_UNKNOWN else ScreeningMode.OFF),
            ),
        )
        "safeWord" -> copy(protection = p.copy(safeWord = p.safeWord.copy(enabled = enabled)))
        "checkIn" -> copy(checkIn = checkIn.copy(enabled = enabled))
        else -> this
    }
}
