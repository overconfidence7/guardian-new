package app.guardianmode.data

import app.guardianmode.core.DeviceConfig
import app.guardianmode.core.GuardianEvent
import kotlinx.serialization.Serializable

/** Which family and device this phone belongs to, set when the parent accepts a setup code. */
@Serializable
data class Pairing(
    val familyId: String,
    val deviceId: String,
    val helperName: String = "",
)

@Serializable
data class PhotoItem(
    val id: String,
    val url: String,
    val fromName: String,
    val caption: String = "",
    val createdAt: Long,
)

@Serializable
data class ChangeItem(
    val id: String,
    val byName: String,
    val summary: String,
    val at: Long,
)

@Serializable
data class HelperInfo(
    val uid: String,
    val name: String,
    val photoUrl: String? = null,
    val role: String = "helper",
    /** "pending" until the parent approves them on this phone. */
    val status: String = "active",
) {
    val pending: Boolean get() = status == "pending"
}

/**
 * Everything the phone keeps locally, persisted as one JSON document so the launcher
 * works offline from the first second and survives process death.
 */
@Serializable
data class LocalState(
    val config: DeviceConfig = DefaultConfig.config,
    val pairing: Pairing? = null,
    val onboardingDone: Boolean = false,
    /** Feature key -> parent's answer on the consent screens. */
    val consent: Map<String, Boolean> = emptyMap(),
    val consentAcceptedAt: Long = 0,
    /** "Pause everything" end time, epoch millis. */
    val pausedUntil: Long = 0,
    /** Events waiting to be uploaded. */
    val outbox: List<GuardianEvent> = emptyList(),
    /** The last events shared with family, newest first, for the transparency screen. */
    val sharedEvents: List<GuardianEvent> = emptyList(),
    val photos: List<PhotoItem> = emptyList(),
    val changes: List<ChangeItem> = emptyList(),
    val helpers: List<HelperInfo> = emptyList(),
    val lastHeartbeatAt: Long = 0,
    val packageSequence: Int = 0,
    val lastInstallCheckAt: Long = 0,
    /** Reminder occurrence key ("id@epochMillis") -> when the parent tapped Done. */
    val remindersDone: Map<String, Long> = emptyMap(),
) {
    fun isPaused(now: Long) = pausedUntil > now

    companion object {
        const val MAX_SHARED_HISTORY = 30
        const val MAX_OUTBOX = 200
    }
}
