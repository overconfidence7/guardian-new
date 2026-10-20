package app.guardianmode.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** qa/antigravity/cases/03-lock-levels-and-code.md, the rule parts (docs/SPEC.md F13, LockPolicy). */
class LockPolicyTest {
    private val locked = LockSettings(level = LockLevel.LOCKED)
    private val guided = LockSettings(level = LockLevel.GUIDED)
    private val simple = LockSettings(level = LockLevel.SIMPLE)
    private val allowed = setOf("app.guardianmode", "com.whatsapp")
    private val m = 60_000L

    @Test
    fun gm_lock_002_codesAreFourToEightDigits() {
        assertFalse(LockCode.isValid("123"), "at least 4 numbers")
        assertTrue(LockCode.isValid("2580"))
        assertTrue(LockCode.isValid("12345678"))
        assertFalse(LockCode.isValid("123456789"), "input stops at 8")
        assertFalse(LockCode.isValid("12a4"), "letters")
        assertFalse(LockCode.isValid("abcd"))
        assertFalse(LockCode.isValid("25 80"))
        assertFalse(LockCode.isValid(""))
        assertFalse(LockCode.isValid("٢٥٨٠"), "only ASCII digits")
    }

    @Test
    fun gm_lock_003_gm_priv_014_onlyASaltedHashIsKept() {
        val salt = "6f1d1e0a-6a1c-4b8e-9d37-1b2f5e0c7a11"
        val h = LockCode.hash("2580", salt)
        assertTrue(Regex("[0-9a-f]{64}").matches(h), h)
        assertFalse("2580" in h)
        assertNotEquals(h, LockCode.hash("2580", "another-salt"), "salted")
        assertEquals(h, LockCode.hash("2580", salt), "deterministic for the same salt")
        assertFalse(LockCode.matches("2580", salt, ""), "no stored hash matches nothing")
    }

    @Test
    fun gm_lock_010_simpleLeavesThePhoneOpen() {
        assertFalse(LockPolicy.needsCode(LockedAction.ALL_APPS, LockLevel.SIMPLE))
        assertFalse(LockPolicy.needsCode(LockedAction.PHONE_SETTINGS, LockLevel.SIMPLE))
        assertFalse(LockPolicy.needsCode(LockedAction.PROTECTION_SETTINGS, LockLevel.SIMPLE))
        for (pkg in listOf("com.android.settings", "com.android.vending", "com.chase.sig.android")) {
            assertFalse(LockPolicy.blocks(pkg, simple, allowed, unlocked = false), pkg)
        }
    }

    @Test
    fun gm_lock_011_simpleStillGuardsLeavingAndTheLock() {
        assertTrue(LockPolicy.needsCode(LockedAction.STOP_USING, LockLevel.SIMPLE))
        assertTrue(LockPolicy.needsCode(LockedAction.LOCK_SETTINGS, LockLevel.SIMPLE))
    }

    @Test
    fun gm_lock_020_guidedGuardsTheAppListSettingsAndSwitches() {
        LockedAction.entries.forEach { assertTrue(LockPolicy.needsCode(it, LockLevel.GUIDED), "$it at GUIDED") }
    }

    @Test
    fun gm_lock_021_guidedLeavesOtherAppsAlone() {
        assertFalse(LockPolicy.blocks("com.android.settings", guided, allowed, unlocked = false))
        assertFalse(LockPolicy.blocks("com.chase.sig.android", guided, allowed, unlocked = false))
    }

    @Test
    fun gm_lock_022_gm_lock_037_aCorrectCodeOpensEverythingForTenMinutes() {
        assertEquals(10 * m, LockPolicy.UNLOCK_MILLIS)
        val enteredAt = 1_000_000L
        val until = enteredAt + LockPolicy.UNLOCK_MILLIS
        val at9 = LockPolicy.isUnlocked(locked, until, enteredAt + 9 * m)
        val at11 = LockPolicy.isUnlocked(locked, until, enteredAt + 11 * m)
        assertTrue(at9)
        assertFalse(at11)
        assertFalse(LockPolicy.isUnlocked(locked, until, until), "the window ends at exactly 10 minutes")
        for (pkg in listOf("com.android.settings", "com.venmo", "com.chase.sig.android")) {
            assertFalse(LockPolicy.blocks(pkg, locked, allowed, unlocked = at9), "$pkg at 9 minutes")
            assertTrue(LockPolicy.blocks(pkg, locked, allowed, unlocked = at11), "$pkg at 11 minutes")
        }
        // The code screens inside Guardian follow the same window (LockManager: needsCode && !isUnlocked).
        assertTrue(LockPolicy.needsCode(LockedAction.PHONE_SETTINGS, LockLevel.GUIDED) && !LockPolicy.isUnlocked(guided, until, enteredAt + 11 * m))
    }

    @Test
    fun gm_lock_032_packageInstallerIsCoveredAtLocked() {
        for (pkg in listOf("com.android.packageinstaller", "com.google.android.packageinstaller", "com.android.vending", "com.android.settings")) {
            assertTrue(LockPolicy.blocks(pkg, locked, allowed, unlocked = false), pkg)
        }
    }

    @Test
    fun gm_lock_033_callsAndEmergencyAreNeverCovered() {
        val calls = listOf(
            "com.android.phone", "com.android.server.telecom", "com.android.emergency", "com.google.android.apps.safetyhub",
            "com.android.incallui", "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer",
            "com.samsung.android.incallui", "com.android.systemui", "android",
        )
        for (pkg in calls) assertFalse(LockPolicy.blocks(pkg, locked, emptySet(), unlocked = false), pkg)
    }

    /** Current behavior; whether the permission controller should stay open is suspect S-12. */
    @Test
    fun gm_lock_034_permissionDialogsAndTheShareSheetAreNotCovered() {
        for (pkg in listOf("com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.android.intentresolver")) {
            assertFalse(LockPolicy.blocks(pkg, locked, emptySet(), unlocked = false), pkg)
        }
    }

    @Test
    fun gm_lock_035_appsTheFamilyAddedAreAllowed() {
        val family = setOf("app.guardianmode", "com.example.notes", "com.google.android.apps.messaging", "com.android.camera2")
        family.forEach { assertFalse(LockPolicy.blocks(it, locked, family, unlocked = false), it) }
        assertTrue(LockPolicy.blocks("com.chase.sig.android", locked, family, unlocked = false))
        assertTrue(LockPolicy.blocks("com.venmo", locked, family, unlocked = false))
    }

    @Test
    fun gm_lock_050_wrongCodeIsRefusedWithoutAWaitAtFirst() {
        val h = LockCode.hash("2580", "salt-1")
        assertTrue(LockCode.matches("2580", "salt-1", h))
        assertFalse(LockCode.matches("2581", "salt-1", h))
        assertFalse(LockCode.matches("", "salt-1", h))
        (1..4).forEach { assertEquals(0, LockCode.waitAfter(it), "after $it wrong tries") }
    }

    @Test
    fun gm_lock_051_fiveWrongTriesStartAThirtySecondWait() {
        assertEquals(5, LockCode.FREE_TRIES)
        assertEquals(30_000, LockCode.waitAfter(5))
    }

    @Test
    fun gm_lock_052_theWaitDoublesUpToSixteenMinutes() {
        val waits = (5..12).map { LockCode.waitAfter(it) }
        assertEquals(listOf(30_000L, m, 2 * m, 4 * m, 8 * m, 16 * m, 16 * m, 16 * m), waits)
        assertEquals(16 * m, LockCode.waitAfter(1_000), "never longer than 16 minutes")
        assertEquals(16 * m, LockCode.waitAfter(Int.MAX_VALUE))
    }

    @Test
    fun gm_lock_060_changingTheLevelChangesWhatIsGuarded() {
        val differs = LockedAction.ALL_APPS
        assertFalse(LockPolicy.needsCode(differs, LockLevel.SIMPLE))
        assertTrue(LockPolicy.needsCode(differs, LockLevel.GUIDED))
        assertTrue(LockPolicy.needsCode(differs, LockLevel.LOCKED))
        assertFalse(LockPolicy.blocks("com.android.settings", guided, allowed, unlocked = false))
        assertTrue(LockPolicy.blocks("com.android.settings", locked, allowed, unlocked = false))
        LockedAction.entries.forEach { assertTrue(LockPolicy.needsCode(it, LockLevel.LOCKED), "$it at LOCKED") }
    }

    @Test
    fun gm_lock_061_aNewCodeWithANewSaltReplacesTheOld() {
        val old = LockCode.hash("2580", "salt-old")
        val new = LockCode.hash("1470", "salt-new")
        assertFalse(LockCode.matches("2580", "salt-new", new))
        assertTrue(LockCode.matches("1470", "salt-new", new))
        assertNotEquals(old, new)
    }

    @Test
    fun remoteUnlockLastsUntilTheLaterOfLocalAndRemote() {
        assertTrue(LockPolicy.isUnlocked(LockSettings(), unlockedUntil = 2_000, now = 1_000))
        assertTrue(LockPolicy.isUnlocked(LockSettings(remoteUnlockUntil = 5_000), unlockedUntil = 0, now = 4_000))
        assertFalse(LockPolicy.isUnlocked(LockSettings(remoteUnlockUntil = 5_000), unlockedUntil = 2_000, now = 6_000))
    }

    @Test
    fun oldConfigsWithoutALockReadAsSimple() {
        val config = GuardianJson.decodeConfig("""{"parentName":"Ann"}""")
        assertEquals(LockLevel.SIMPLE, config.lock.level)
    }
}
