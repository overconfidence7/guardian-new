package app.guardianmode.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LockPolicyTest {
    private val locked = LockSettings(level = LockLevel.LOCKED)
    private val allowed = setOf("app.guardianmode", "com.whatsapp")

    @Test
    fun simpleOnlyGuardsLeavingAndTheLockItself() {
        assertTrue(LockPolicy.needsCode(LockedAction.STOP_USING, LockLevel.SIMPLE))
        assertTrue(LockPolicy.needsCode(LockedAction.LOCK_SETTINGS, LockLevel.SIMPLE))
        assertFalse(LockPolicy.needsCode(LockedAction.ALL_APPS, LockLevel.SIMPLE))
        assertFalse(LockPolicy.needsCode(LockedAction.PROTECTION_SETTINGS, LockLevel.SIMPLE))
    }

    @Test
    fun guidedAndLockedGuardEverything() {
        for (level in listOf(LockLevel.GUIDED, LockLevel.LOCKED)) {
            LockedAction.entries.forEach { assertTrue(LockPolicy.needsCode(it, level), "$it at $level") }
        }
    }

    @Test
    fun onlyLockedCoversOtherApps() {
        assertTrue(LockPolicy.blocks("com.android.settings", locked, allowed, unlocked = false))
        assertFalse(LockPolicy.blocks("com.android.settings", LockSettings(level = LockLevel.GUIDED), allowed, unlocked = false))
        assertFalse(LockPolicy.blocks("com.android.settings", locked, allowed, unlocked = true))
    }

    @Test
    fun allowedAppsAndCallsAreNeverCovered() {
        assertFalse(LockPolicy.blocks("com.whatsapp", locked, allowed, unlocked = false))
        assertFalse(LockPolicy.blocks("com.google.android.dialer", locked, allowed, unlocked = false))
        assertFalse(LockPolicy.blocks("com.android.phone", locked, allowed, unlocked = false))
        assertFalse(LockPolicy.blocks("com.android.systemui", locked, allowed, unlocked = false))
    }

    @Test
    fun unlockLastsUntilTheLaterOfLocalAndRemote() {
        assertTrue(LockPolicy.isUnlocked(LockSettings(), unlockedUntil = 2_000, now = 1_000))
        assertTrue(LockPolicy.isUnlocked(LockSettings(remoteUnlockUntil = 5_000), unlockedUntil = 0, now = 4_000))
        assertFalse(LockPolicy.isUnlocked(LockSettings(remoteUnlockUntil = 5_000), unlockedUntil = 2_000, now = 6_000))
    }

    @Test
    fun codesAreFourToEightDigits() {
        assertTrue(LockCode.isValid("1234"))
        assertTrue(LockCode.isValid("12345678"))
        assertFalse(LockCode.isValid("123"))
        assertFalse(LockCode.isValid("123456789"))
        assertFalse(LockCode.isValid("12a4"))
    }

    @Test
    fun hashMatchesOnlyTheSameCodeAndSalt() {
        val h = LockCode.hash("2468", "salt-1")
        assertTrue(LockCode.matches("2468", "salt-1", h))
        assertFalse(LockCode.matches("2469", "salt-1", h))
        assertFalse(LockCode.matches("2468", "salt-2", h))
        assertFalse(LockCode.matches("2468", "salt-1", ""))
    }

    @Test
    fun wrongTriesWaitLongerAndLonger() {
        assertEquals(0, LockCode.waitAfter(4))
        assertEquals(30_000, LockCode.waitAfter(5))
        assertEquals(60_000, LockCode.waitAfter(6))
        assertEquals(960_000, LockCode.waitAfter(20))
    }

    @Test
    fun oldConfigsWithoutALockReadAsSimple() {
        val config = GuardianJson.decodeConfig("""{"parentName":"Ann"}""")
        assertEquals(LockLevel.SIMPLE, config.lock.level)
    }
}
