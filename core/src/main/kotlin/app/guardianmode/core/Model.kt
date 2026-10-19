package app.guardianmode.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The device configuration is written by family members from the web dashboard and
 * read by the parent's phone. The JSON shape is the contract between the two and is
 * mirrored in shared/schema/device-config.schema.json and web/src/lib/model.ts.
 * Every field has a default so older phones can read newer documents and vice versa.
 */

@Serializable
data class DeviceConfig(
    val schemaVersion: Int = SCHEMA_VERSION,
    val revision: Long = 0,
    val parentName: String = "",
    val family: List<FamilyContact> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val home: HomeLayout = HomeLayout(),
    val display: DisplaySettings = DisplaySettings(),
    val allowedApps: List<String> = emptyList(),
    val protection: ProtectionSettings = ProtectionSettings(),
    val checkIn: CheckInSettings = CheckInSettings(),
    val reminders: List<Reminder> = emptyList(),
    val lock: LockSettings = LockSettings(),
) {
    /** The family member the parent's "Call ___ first" buttons dial. */
    val primaryHelper: FamilyContact?
        get() = family.firstOrNull { it.isPrimaryHelper && it.phone.isNotBlank() }
            ?: family.firstOrNull { it.phone.isNotBlank() }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** How locked down the phone is. See LockPolicy for what each level covers. */
@Serializable
data class LockSettings(
    val level: LockLevel = LockLevel.SIMPLE,
    /** A helper's temporary unlock from the dashboard, epoch millis. */
    val remoteUnlockUntil: Long = 0,
)

@Serializable
enum class LockLevel {
    /** Big home screen; only leaving Guardian or changing the lock needs the code. */
    SIMPLE,

    /** Also the app list, phone settings and protection switches need the code. */
    GUIDED,

    /** Also any app the family didn't pick, Settings included, is covered by a code screen. */
    LOCKED,
}

@Serializable
data class FamilyContact(
    val id: String,
    val name: String,
    val relation: String = "",
    val phone: String = "",
    val photoUrl: String? = null,
    val isPrimaryHelper: Boolean = false,
)

@Serializable
data class Contact(
    val id: String,
    val name: String,
    val phone: String,
    val photoUrl: String? = null,
    val note: String = "",
)

@Serializable
data class HomeLayout(
    val tiles: List<HomeTile> = emptyList(),
    val showPhotoCard: Boolean = true,
    val columns: Int = 2,
)

@Serializable
data class HomeTile(
    val id: String,
    val kind: TileKind,
    /** Contact id for CALL_CONTACT, package name for APP, unused otherwise. */
    val target: String = "",
    val label: String = "",
)

@Serializable
enum class TileKind { CALL_CONTACT, APP, PHONE, MESSAGES, CAMERA, PHOTOS, REMINDERS, HELP, ALL_APPS }

@Serializable
data class DisplaySettings(
    /** Multiplier on the base type scale. 1.0 is already larger than stock Android. */
    val fontScale: Float = 1.3f,
    val highContrast: Boolean = false,
    val theme: ThemePreference = ThemePreference.LIGHT,
)

@Serializable
enum class ThemePreference { LIGHT, DARK, SYSTEM }

@Serializable
data class ProtectionSettings(
    val pauseTripwires: PauseSettings = PauseSettings(),
    val remoteAccessAlarm: RemoteAccessSettings = RemoteAccessSettings(),
    val callScreening: ScreeningSettings = ScreeningSettings(),
    val longCallAlert: LongCallSettings = LongCallSettings(),
    val safeWord: SafeWordSettings = SafeWordSettings(),
)

@Serializable
data class PauseSettings(
    val enabled: Boolean = true,
    /** How long after an unknown call ends the tripwires stay armed. */
    val windowMinutes: Int = 30,
    val categories: Set<AppCategory> = setOf(
        AppCategory.PAYMENT, AppCategory.GIFT_CARD, AppCategory.CRYPTO, AppCategory.BANK,
    ),
    /** Family-added packages, e.g. the parent's local credit-union app. */
    val extraPackages: Map<String, AppCategory> = emptyMap(),
)

@Serializable
data class RemoteAccessSettings(
    val enabled: Boolean = true,
    /** Remote-access apps the family itself uses to help; these never alarm. */
    val trustedPackages: Set<String> = emptySet(),
    /** Also warn on any newly installed app that can control the screen. */
    val warnOnControlCapableInstalls: Boolean = true,
)

@Serializable
data class ScreeningSettings(
    val mode: ScreeningMode = ScreeningMode.SILENCE_UNKNOWN,
    val allowlist: List<AllowedNumber> = emptyList(),
    /** A second call from the same unknown number within a few minutes rings normally. */
    val allowRepeatCallers: Boolean = true,
)

@Serializable
enum class ScreeningMode {
    /** Every call rings as usual; Guardian only notes unknown callers. */
    OFF,

    /** Unknown callers ring silently and go to voicemail if not answered. */
    SILENCE_UNKNOWN,

    /** Unknown callers go straight to voicemail. */
    VOICEMAIL_UNKNOWN,
}

@Serializable
data class AllowedNumber(val label: String, val number: String)

@Serializable
data class LongCallSettings(val enabled: Boolean = true, val minutes: Int = 20)

@Serializable
data class SafeWordSettings(
    val enabled: Boolean = true,
    /** Stays on the parent's phone so they can look it up; never sent back in events. */
    val word: String = "",
)

@Serializable
data class CheckInSettings(
    val enabled: Boolean = true,
    /** Local time ("HH:mm") by which the phone should have been used today. */
    val deadline: String = "11:00",
    val timezone: String = "America/New_York",
)

@Serializable
data class Reminder(
    val id: String,
    val title: String,
    val detail: String = "",
    val kind: ReminderKind = ReminderKind.MEDICATION,
    /** Local times "HH:mm". */
    val times: List<String> = emptyList(),
    /** ISO day numbers 1 (Monday) to 7 (Sunday). Empty means every day. */
    val daysOfWeek: Set<Int> = emptySet(),
    /** For one-off appointments: ISO date "yyyy-MM-dd". */
    val date: String? = null,
    /** Tell the family if a medication reminder is not confirmed within an hour. */
    val notifyFamilyIfMissed: Boolean = false,
)

@Serializable
enum class ReminderKind { MEDICATION, APPOINTMENT, OTHER }

@Serializable
enum class AppCategory { PAYMENT, GIFT_CARD, CRYPTO, BANK, REMOTE_ACCESS }

/*
 * Events flow from the parent's phone to the family. They carry what happened, never
 * content: no message text, no call audio, no full phone numbers, no screen contents
 * except a screenshot the parent explicitly chose to send with "Help me".
 */

@Serializable
data class GuardianEvent(
    val id: String,
    val type: EventType,
    val at: Long,
    val severity: Severity,
    val data: Map<String, String> = emptyMap(),
)

@Serializable
enum class EventType {
    LONG_UNKNOWN_CALL,
    PAUSE_SHOWN,
    PAUSE_CALLED_FAMILY,
    PAUSE_CONTINUED,
    REMOTE_ACCESS_INSTALLED,
    REMOTE_ACCESS_OPENED,
    CONTROL_APP_INSTALLED,
    HELP_REQUESTED,
    REMINDER_MISSED,
    PROTECTION_CHANGED_BY_PARENT,
    PERMISSION_MISSING,
    CHECK_IN_MISSED,
}

@Serializable
enum class Severity { INFO, NOTICE, URGENT }

object GuardianJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun decodeConfig(text: String): DeviceConfig = json.decodeFromString(DeviceConfig.serializer(), text)
    fun encodeConfig(config: DeviceConfig): String = json.encodeToString(DeviceConfig.serializer(), config)
    fun encodeEvent(event: GuardianEvent): String = json.encodeToString(GuardianEvent.serializer(), event)
    fun decodeEvent(text: String): GuardianEvent = json.decodeFromString(GuardianEvent.serializer(), text)
}
