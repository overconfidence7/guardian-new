package app.guardianmode.core

import java.security.MessageDigest

/** Places in Guardian that can sit behind the lock code. */
enum class LockedAction { ALL_APPS, PHONE_SETTINGS, PROTECTION_SETTINGS, STOP_USING, LOCK_SETTINGS }

/**
 * What the lock code protects at each level. A normal home app can't stop Android's
 * own ways out (safe mode, factory reset), and it shouldn't: the lock keeps a phone
 * simple, it doesn't trap anyone. Calls, including emergency calls, are never blocked.
 */
object LockPolicy {
    /** How long a correct code keeps everything open. */
    const val UNLOCK_MILLIS = 10 * 60_000L

    /**
     * How long Android's uninstall screen stays uncovered after "Remove" on a remote-access
     * warning, so the parent can confirm without the code. Anything else there still needs it.
     */
    const val UNINSTALL_PASS_MILLIS = 30_000L

    /** Android's install and uninstall screens. */
    val PACKAGE_INSTALLERS: Set<String> = setOf("com.android.packageinstaller", "com.google.android.packageinstaller")

    /** System screens that must always work, whatever the level. */
    val ALWAYS_ALLOWED: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.intentresolver",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.emergency",
        "com.google.android.apps.safetyhub",
        "com.android.incallui",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
    )

    /**
     * Android's permission app hosts both the "Allow this app to...?" prompts, which must
     * always show, and the permission manager, where anyone could switch Guardian's Phone
     * or Contacts access off. Only the prompts get through at Locked.
     */
    private val PERMISSION_CONTROLLERS = setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller")
    private val PERMISSION_PROMPTS = listOf("GrantPermissionsActivity", "RequestRoleActivity")

    fun needsCode(action: LockedAction, level: LockLevel): Boolean = when (level) {
        LockLevel.SIMPLE -> action == LockedAction.STOP_USING || action == LockedAction.LOCK_SETTINGS
        LockLevel.GUIDED, LockLevel.LOCKED -> true
    }

    fun isUnlocked(settings: LockSettings, unlockedUntil: Long, now: Long): Boolean =
        now < maxOf(unlockedUntil, settings.remoteUnlockUntil)

    /**
     * Whether a code screen should cover [packageName] now that it is in front.
     * [allowed] is every package the family put on the phone (tiles, allowed apps,
     * the phone's own dialer, messages and camera) plus Guardian itself.
     */
    fun blocks(
        packageName: String,
        settings: LockSettings,
        allowed: Set<String>,
        unlocked: Boolean,
        className: String = "",
        uninstallPass: Boolean = false,
    ): Boolean =
        settings.level == LockLevel.LOCKED && !unlocked &&
            packageName !in allowed && packageName !in ALWAYS_ALLOWED && !isPermissionPrompt(packageName, className) &&
            !(uninstallPass && packageName in PACKAGE_INSTALLERS)

    private fun isPermissionPrompt(packageName: String, className: String): Boolean =
        // A screen we can't name is let through rather than risk covering a prompt the parent needs.
        packageName in PERMISSION_CONTROLLERS && (className.isBlank() || PERMISSION_PROMPTS.any { className.endsWith(".$it") })
}

/** The parent's lock code. Only a salted hash is kept, and only on the phone. */
object LockCode {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 8
    const val FREE_TRIES = 5

    fun isValid(code: String): Boolean = code.length in MIN_LENGTH..MAX_LENGTH && code.all { it in '0'..'9' }

    fun hash(code: String, salt: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$code".toByteArray()).joinToString("") { "%02x".format(it) }

    fun matches(code: String, salt: String, hash: String): Boolean =
        hash.isNotEmpty() && MessageDigest.isEqual(hash(code, salt).toByteArray(), hash.toByteArray())

    /** After a few wrong tries, each one makes the next wait longer: 30 s, 1 min, 2 min, up to 16 min. */
    fun waitAfter(failures: Int): Long =
        if (failures < FREE_TRIES) 0 else 30_000L shl (failures - FREE_TRIES).coerceAtMost(5)
}
