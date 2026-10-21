package app.guardianmode

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.guardianmode.core.LockLevel
import app.guardianmode.core.LockedAction
import app.guardianmode.data.LocalStore
import app.guardianmode.protection.LockManager
import app.guardianmode.protection.UnlockResult
import app.guardianmode.ui.cards.CardContent
import app.guardianmode.ui.cards.CardKind
import app.guardianmode.ui.cards.CardRequest
import app.guardianmode.ui.components.unlockMessage
import app.guardianmode.ui.lock.UnlockScreen
import app.guardianmode.ui.theme.GuardianTheme
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** qa/antigravity/cases/03-lock-levels-and-code.md: the code, the wait, and what each level guards. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class LockCodeTest {

    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private lateinit var dir: File
    private lateinit var store: LocalStore
    private lateinit var scope: CoroutineScope
    private var now = Fixtures.now.toInstant().toEpochMilli()
    private lateinit var lock: LockManager

    @Before fun open() {
        dir = tmp.newFolder("files")
        reopen()
    }

    @After fun closeStore() = UiChecks.close(scope)

    private fun reopen() {
        if (::scope.isInitialized) UiChecks.close(scope)
        UiChecks.openStore(context, dir).let { (s, sc) -> store = s; scope = sc }
        lock = LockManager(context, store) { now }
    }

    private fun setUp(level: LockLevel, code: String? = "2580") = runBlocking {
        store.update { it.copy(onboardingDone = true, config = it.config.copy(lock = it.config.lock.copy(level = level))) }
        if (code != null) lock.setCode(code)
        settle()
    }

    private fun attempt(code: String) = runBlocking { lock.tryUnlock(code) }.also { settle() }

    /** store.current follows the file a moment later; wait until it has caught up. */
    private fun settle() = runBlocking {
        val latest = store.update { it }
        store.state.first { it == latest }
    }

    @Test fun gm_lock_050_wrongCodeMessageClearsFieldAndIsReadOut() {
        compose.setContent { GuardianTheme { UnlockScreen(tryUnlock = { UnlockResult.Wrong }, onUnlocked = {}, onBack = {}) } }
        compose.onNodeWithText("Enter the code").assertExists()
        compose.onNodeWithText("This part of the phone needs the lock code.").assertExists()
        val field = compose.onNode(hasContentDescription("Lock code") and hasSetTextAction())
        field.performTextInput("1111")
        compose.onNodeWithText("Unlock").performClick()
        compose.onNodeWithText("That's not the code. Try again.")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        assertThat(unlockMessage(UnlockResult.Wrong)).isEqualTo("That's not the code. Try again.")
    }

    @Test fun gm_lock_051_fiveWrongTriesStartA30SecondWait() {
        setUp(LockLevel.GUIDED)
        repeat(4) { assertThat(attempt("1111")).isEqualTo(UnlockResult.Wrong) }
        val fifth = attempt("1111")
        assertThat(fifth).isEqualTo(UnlockResult.Wait(now + 30_000))
        assertThat(unlockMessage(fifth, Fixtures.zone)).isEqualTo("That's too many tries. You can try again at 9:41 AM.")

        now += 1_000
        assertThat(attempt("2580")).isEqualTo(UnlockResult.Wait(Fixtures.now.toInstant().toEpochMilli() + 30_000))

        now = Fixtures.now.toInstant().toEpochMilli() + 30_000
        assertThat(attempt("2580")).isEqualTo(UnlockResult.Unlocked)
        assertThat(store.current.lockFailures).isEqualTo(0)
        assertThat(store.current.lockRetryAt).isEqualTo(0)
    }

    @Test fun gm_lock_051_waitMessageShowsOnTheUnlockScreen() {
        setUp(LockLevel.GUIDED)
        repeat(4) { attempt("1111") }
        compose.setContent { GuardianTheme { UnlockScreen(tryUnlock = lock::tryUnlock, onUnlocked = {}, onBack = {}) } }
        val field = compose.onNode(hasContentDescription("Lock code") and hasSetTextAction())
        field.performTextInput("1111")
        compose.onNodeWithText("Unlock").performClick()
        compose.waitUntil(5_000) { store.current.lockRetryAt > 0 }
        compose.onNodeWithText("That's too many tries", substring = true).assertExists()
    }

    @Test fun gm_lock_052_waitDoublesUpTo16Minutes() {
        setUp(LockLevel.GUIDED)
        repeat(4) { attempt("1111") }
        val waits = (1..7).map {
            val result = attempt("1111") as UnlockResult.Wait
            val wait = result.retryAt - now
            now = result.retryAt // the wait ends
            wait
        }
        assertThat(waits).containsExactly(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 960_000L, 960_000L).inOrder()
    }

    @Test fun gm_lock_053_waitSurvivesARestart() {
        setUp(LockLevel.GUIDED)
        repeat(5) { attempt("1111") }
        val retryAt = store.current.lockRetryAt
        reopen()
        assertThat(store.current.lockRetryAt).isEqualTo(retryAt)
        assertThat(attempt("2580")).isEqualTo(UnlockResult.Wait(retryAt))
    }

    @Test fun gm_lock_003_gm_priv_014_onlyASaltedHashIsSaved() {
        setUp(LockLevel.GUIDED, code = "2580")
        val s = store.current
        assertThat(s.lockCodeHash).matches("[0-9a-f]{64}")
        assertThat(java.util.UUID.fromString(s.lockCodeSalt).toString()).isEqualTo(s.lockCodeSalt)
        reopen() // forces the file to be read back
        val saved = UiChecks.stateFile(dir).readText()
        assertThat(saved.replace(s.lockCodeHash, "").replace(s.lockCodeSalt, "")).doesNotContain("2580")
        // A second phone with the same code gets a different hash.
        val firstHash = s.lockCodeHash
        runBlocking { lock.setCode("2580") }
        settle()
        assertThat(store.current.lockCodeHash).isNotEqualTo(firstHash)
    }

    @Test fun gm_lock_010_gm_lock_011_gm_lock_020_whatEachLevelGuards() {
        val expected = mapOf(
            LockLevel.SIMPLE to setOf(LockedAction.STOP_USING, LockedAction.LOCK_SETTINGS),
            LockLevel.GUIDED to LockedAction.entries.toSet(),
            LockLevel.LOCKED to LockedAction.entries.toSet(),
        )
        expected.forEach { (level, guarded) ->
            setUp(level)
            runBlocking { lock.relock() }.also { settle() }
            LockedAction.entries.forEach { action ->
                assertWithMessage("$level $action").that(lock.needsCode(action)).isEqualTo(action in guarded)
            }
        }
    }

    @Test fun gm_lock_030_gm_lock_035_lockedCoversSettingsButNotFamilyApps() {
        setUp(LockLevel.LOCKED)
        runBlocking { store.update { it.copy(config = it.config.copy(allowedApps = listOf("com.example.notes"))) } }
        settle()
        assertThat(lock.blocks("com.android.settings")).isTrue()
        assertThat(lock.blocks("com.chase.sig.android")).isTrue()
        assertThat(lock.blocks("com.example.notes")).isFalse()
        assertThat(lock.blocks(context.packageName)).isFalse()

        // Guided never covers other apps (GM-LOCK-021).
        setUp(LockLevel.GUIDED)
        assertThat(lock.blocks("com.android.settings")).isFalse()
    }

    @Test fun gm_lock_030_lockedAppCardWording() {
        val card = object : app.guardianmode.ui.cards.CardActions {
            override fun callFamily(phone: String) = Unit
            override fun continueAnyway() = Unit
            override fun goHome() = Unit
            override fun removeApp() = Unit
            override fun reminderDone() = Unit
            override fun snooze() = Unit
            override fun dismiss() = Unit
        }
        compose.setContent {
            GuardianTheme { CardContent(CardRequest(CardKind.LOCKED, "com.android.settings", "Settings"), Fixtures.config, card) }
        }
        compose.onNodeWithText("Settings is locked").assertExists()
        compose.onNodeWithText("This phone is set up to keep things simple. To open Settings, enter the code.").assertExists()
        compose.onNodeWithText("Your code").assertExists()
        compose.onNode(hasSetTextAction()).assertExists()
        compose.onNodeWithText("Unlock").assertExists()
        compose.onNodeWithText("Go to my home screen").assertExists()
    }

    @Test fun gm_lock_037_rightCodeOpensEverythingFor10Minutes() {
        setUp(LockLevel.LOCKED)
        val start = now
        assertThat(attempt("2580")).isEqualTo(UnlockResult.Unlocked)
        assertThat(lock.blocks("com.android.settings")).isFalse()
        assertThat(lock.needsCode(LockedAction.ALL_APPS)).isFalse()
        now = start + 9 * 60_000
        assertThat(lock.blocks("com.android.settings")).isFalse()
        now = start + 11 * 60_000
        assertThat(lock.blocks("com.android.settings")).isTrue()
        assertThat(lock.needsCode(LockedAction.ALL_APPS)).isTrue()
    }

    @Test fun gm_lock_038_lockItAgainNowTakesEffectAtOnce() {
        setUp(LockLevel.LOCKED)
        attempt("2580")
        assertThat(lock.isUnlocked()).isTrue()
        runBlocking { lock.relock() }.also { settle() }
        assertThat(lock.isUnlocked()).isFalse()
        assertThat(lock.blocks("com.android.settings")).isTrue()
    }

    @Test fun gm_lock_061_changingTheCodeRetiresTheOldOne() {
        setUp(LockLevel.GUIDED)
        val oldSalt = store.current.lockCodeSalt
        runBlocking { lock.setCode("1470") }
        settle()
        assertThat(store.current.lockCodeSalt).isNotEqualTo(oldSalt)
        assertThat(attempt("2580")).isEqualTo(UnlockResult.Wrong)
        assertThat(attempt("1470")).isEqualTo(UnlockResult.Unlocked)
    }

    @Test fun gm_lock_062_noCodeMeansNothingIsLocked() {
        setUp(LockLevel.LOCKED, code = null)
        assertThat(lock.hasCode).isFalse()
        LockedAction.entries.forEach { assertWithMessage(it.name).that(lock.needsCode(it)).isFalse() }
        assertThat(lock.blocks("com.android.settings")).isFalse()
    }
}
