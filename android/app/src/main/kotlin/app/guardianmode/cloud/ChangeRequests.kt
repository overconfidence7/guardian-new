package app.guardianmode.cloud

import app.guardianmode.core.DeviceConfig
import app.guardianmode.core.FamilyContact
import app.guardianmode.core.GuardianJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A helper asked for a change only the parent can make (docs/SECURITY.md §3), stored at
 * families/{f}/devices/{d}/requests/{id} (docs/API.md). The phone shows it on the home
 * screen; on "Yes" it applies [changes] to the config itself, on "No" nothing changes.
 * Keys match firebase/functions/src/lib/changeRequests.ts.
 */
data class ChangeRequest(
    val id: String,
    val byUid: String,
    val byName: String,
    /** Only the keys being asked for, each with the value wanted (raw Firestore values). */
    val changes: Map<String, Any?>,
)

object ChangeRequests {
    const val FAMILY = "family"
    const val TRUSTED_PACKAGES = "trustedPackages"
    const val PAUSE = "pauseTripwiresEnabled"
    const val REMOTE_ACCESS = "remoteAccessAlarmEnabled"
    const val LONG_CALL = "longCallAlertEnabled"
    const val REMOTE_UNLOCK = "remoteUnlockUntil"

    private val familyListSerializer = ListSerializer(FamilyContact.serializer())

    /** The config with the requested changes in place. Unknown keys and unreadable values are skipped. */
    fun apply(config: DeviceConfig, changes: Map<String, Any?>): DeviceConfig {
        var c = config
        (changes[FAMILY] as? List<*>)?.let { raw ->
            runCatching { GuardianJson.json.decodeFromJsonElement(familyListSerializer, FirestoreJson.toJson(raw)) }
                .getOrNull()?.let { c = c.copy(family = it) }
        }
        (changes[TRUSTED_PACKAGES] as? List<*>)?.let { raw ->
            val packages = raw.filterIsInstance<String>().toSet()
            c = c.copy(protection = c.protection.copy(remoteAccessAlarm = c.protection.remoteAccessAlarm.copy(trustedPackages = packages)))
        }
        (changes[PAUSE] as? Boolean)?.let { on ->
            c = c.copy(protection = c.protection.copy(pauseTripwires = c.protection.pauseTripwires.copy(enabled = on)))
        }
        (changes[REMOTE_ACCESS] as? Boolean)?.let { on ->
            c = c.copy(protection = c.protection.copy(remoteAccessAlarm = c.protection.remoteAccessAlarm.copy(enabled = on)))
        }
        (changes[LONG_CALL] as? Boolean)?.let { on ->
            c = c.copy(protection = c.protection.copy(longCallAlert = c.protection.longCallAlert.copy(enabled = on)))
        }
        (changes[REMOTE_UNLOCK] as? Number)?.let { until ->
            c = c.copy(lock = c.lock.copy(remoteUnlockUntil = until.toLong()))
        }
        return c
    }

    /**
     * What the parent is asked, in their words: "turn off the pause before sending money".
     * Built from the change itself, never from text the helper typed.
     */
    fun describe(changes: Map<String, Any?>, now: Long): String {
        val parts = buildList {
            if (FAMILY in changes) add("change the phone numbers for your family or who gets called first")
            if (TRUSTED_PACKAGES in changes) add("let a remote-control app open without a warning")
            (changes[PAUSE] as? Boolean)?.let { add(if (it) "turn on the pause before sending money" else "turn off the pause before sending money") }
            (changes[REMOTE_ACCESS] as? Boolean)?.let {
                add(if (it) "turn on the warning about remote-control apps" else "turn off the warning about remote-control apps")
            }
            (changes[LONG_CALL] as? Boolean)?.let {
                add(if (it) "tell your family about long calls again" else "stop telling your family about long calls with unknown numbers")
            }
            (changes[REMOTE_UNLOCK] as? Number)?.let {
                val minutes = ((it.toLong() - now) / 60_000).coerceAtLeast(1)
                add(if (minutes >= 120) "unlock your phone for ${minutes / 60} hours" else "unlock your phone for $minutes minutes")
            }
        }
        return when (parts.size) {
            0 -> "change a setting"
            1 -> parts[0]
            else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        }
    }
}

/**
 * Three-way merge of config JSON, the same rule as the dashboard's web/src/lib/merge.ts:
 * take the phone's value only where the phone changed it since [base] (the last config it
 * received), so a helper's edit to another setting made meanwhile is kept. Lists are whole
 * values; where both sides changed the same thing, the phone (the parent) wins.
 */
object ConfigMerge {
    fun threeWay(base: JsonElement?, local: JsonElement?, remote: JsonElement?): JsonElement? {
        if (local == base) return remote
        if (remote == base) return local
        if (base is JsonObject && local is JsonObject && remote is JsonObject) {
            val keys = base.keys + local.keys + remote.keys
            return JsonObject(keys.mapNotNull { k -> threeWay(base[k], local[k], remote[k])?.let { k to it } }.toMap())
        }
        return local
    }
}
