package app.guardianmode.protection

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.provider.Telephony
import android.telecom.TelecomManager
import app.guardianmode.core.LockCode
import app.guardianmode.core.LockLevel
import app.guardianmode.core.LockPolicy
import app.guardianmode.core.LockedAction
import app.guardianmode.core.TileKind
import app.guardianmode.data.LocalStore
import java.util.UUID

sealed interface UnlockResult {
    data object Unlocked : UnlockResult
    data object Wrong : UnlockResult
    /** Too many wrong tries; the next one is allowed at [retryAt]. */
    data class Wait(val retryAt: Long) : UnlockResult
}

/** The parent's lock code and what it guards, on top of the pure rules in LockPolicy. */
class LockManager(
    private val context: Context,
    private val store: LocalStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val hasCode: Boolean get() = store.current.lockCodeHash.isNotEmpty()

    val level: LockLevel get() = store.current.config.lock.level

    fun isUnlocked(): Boolean {
        val s = store.current
        return LockPolicy.isUnlocked(s.config.lock, s.unlockedUntil, clock())
    }

    /** True when [action] should ask for the code first. Without a code nothing is locked. */
    fun needsCode(action: LockedAction): Boolean =
        hasCode && store.current.onboardingDone && !isUnlocked() && LockPolicy.needsCode(action, level)

    /** True when a code screen should cover [packageName], which just came to the front. */
    fun blocks(packageName: String): Boolean {
        val s = store.current
        if (!hasCode || !s.onboardingDone || s.config.lock.level != LockLevel.LOCKED) return false
        return LockPolicy.blocks(packageName, s.config.lock, allowedPackages(), isUnlocked())
    }

    suspend fun setCode(code: String) {
        require(LockCode.isValid(code))
        val salt = UUID.randomUUID().toString()
        store.update { it.copy(lockCodeHash = LockCode.hash(code, salt), lockCodeSalt = salt, lockFailures = 0, lockRetryAt = 0) }
    }

    suspend fun tryUnlock(code: String): UnlockResult {
        val now = clock()
        val s = store.current
        if (s.lockRetryAt > now) return UnlockResult.Wait(s.lockRetryAt)
        if (LockCode.matches(code, s.lockCodeSalt, s.lockCodeHash)) {
            store.update { it.copy(unlockedUntil = now + LockPolicy.UNLOCK_MILLIS, lockFailures = 0, lockRetryAt = 0) }
            return UnlockResult.Unlocked
        }
        val failures = s.lockFailures + 1
        val wait = LockCode.waitAfter(failures)
        store.update { it.copy(lockFailures = failures, lockRetryAt = if (wait > 0) now + wait else 0) }
        return if (wait > 0) UnlockResult.Wait(now + wait) else UnlockResult.Wrong
    }

    /** Locks again straight away, for when the parent is done with an unlocked app. */
    suspend fun relock() {
        store.update { it.copy(unlockedUntil = 0) }
    }

    /** Everything the family put on the phone, plus the phone's own calling, texting and camera apps. */
    fun allowedPackages(): Set<String> {
        val config = store.current.config
        val out = mutableSetOf(context.packageName)
        out += config.allowedApps
        config.home.tiles.filter { it.kind == TileKind.APP }.mapTo(out) { it.target }
        runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()?.let(out::add)
        runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()?.let(out::add)
        val pm = context.packageManager
        listOf(
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
            // Recents and the old home screen belong to the phone's own launcher.
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
        ).forEach { intent ->
            runCatching { pm.queryIntentActivities(intent, 0) }.getOrNull()
                ?.map { it.activityInfo.packageName }
                ?.filterNot { it in NEVER_BY_INTENT }
                ?.let(out::addAll)
        }
        return out
    }

    private companion object {
        /** Settings registers a fallback home screen; that must not open Settings to everyone. */
        val NEVER_BY_INTENT = setOf(
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
        )
    }
}
