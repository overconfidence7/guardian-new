package app.guardianmode.protection

import android.content.Context
import android.app.NotificationManager
import app.guardianmode.cloud.CloudSync
import app.guardianmode.cloud.DeviceStatus
import app.guardianmode.core.DeviceConfig
import app.guardianmode.core.ScreeningMode
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
        store.update { it.copy(pausedUntil = clock() + millis) }
        refreshNotification()
    }

    suspend fun resume() {
        store.update { it.copy(pausedUntil = 0) }
        refreshNotification()
    }

    private fun refreshNotification() {
        if (!store.current.onboardingDone) return
        context.getSystemService(NotificationManager::class.java)
            .notify(Notifications.ID_PROTECTION, Notifications.protection(context, store.current.isPaused(clock())))
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

    /** Called when the phone is unlocked or the home screen is shown: the passive "I'm OK". */
    suspend fun onUserActive() {
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
        val needed = Capabilities.BY_FEATURE.filterKeys { consent[it] == true }.values.flatten().distinct()
        val missing = capabilities.missing(needed)
        cloud.reportStatus(
            DeviceStatus(
                protected = missing.isEmpty() && !store.current.isPaused(clock()),
                missing = missing.map { it.key },
                installedApps = runCatching { installs.launchableApps() }.getOrDefault(emptyList()),
            ),
        )
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
