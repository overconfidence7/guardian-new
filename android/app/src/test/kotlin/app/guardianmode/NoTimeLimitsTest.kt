package app.guardianmode

import android.app.NotificationManager
import android.os.Looper
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.guardianmode.UiChecks.allText
import app.guardianmode.core.LockLevel
import app.guardianmode.core.LockPolicy
import app.guardianmode.protection.LockManager
import app.guardianmode.protection.UnlockResult
import app.guardianmode.ui.cards.CardKind
import app.guardianmode.ui.cards.CardRequest
import app.guardianmode.ui.cards.GuardianCardActivity
import app.guardianmode.ui.components.UnlockForm
import app.guardianmode.ui.theme.GuardianTheme
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.util.TimeZone

/**
 * GM-A11Y-005 (qa/antigravity/cases/15-accessibility.md): nothing times out on the parent. The
 * cards and the code entry wait for as long as the parent needs, the lock-code wait is a clock
 * time that doesn't tick, and the 10-minute unlock window ends without anything popping up.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class NoTimeLimitsTest {

    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val app get() = ApplicationProvider.getApplicationContext<GuardianApp>()

    /** An hour goes by on the main thread's clock and on Compose's. */
    private fun anHourPasses() {
        repeat(60) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
            rule.mainClock.advanceTimeBy(60_000)
        }
        rule.waitForIdle()
    }

    private fun waitsAnHour(request: CardRequest, expected: List<String>) {
        runBlocking { app.container.store.update { UiChecks.familyState().copy(lockCodeHash = "") } }
        ActivityScenario.launch<GuardianCardActivity>(GuardianCardActivity.intent(app, request)).use { scenario ->
            rule.waitForIdle()
            expected.forEach { rule.onNodeWithText(it).assertExists() }
            val typed = if (request.kind == CardKind.LOCKED) {
                rule.onNode(hasContentDescription("Lock code") and hasSetTextAction()).performTextInput("25")
                true
            } else {
                false
            }
            anHourPasses()
            assertWithMessage("${request.kind} card still open after an hour").that(scenario.state).isEqualTo(Lifecycle.State.RESUMED)
            scenario.onActivity { assertThat(it.isFinishing).isFalse() }
            expected.forEach { rule.onNodeWithText(it).assertExists() }
            if (typed) {
                // Half-typed digits are still there: nothing cleared the field while the parent thought.
                rule.onNode(hasContentDescription("Lock code") and hasSetTextAction())
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("25")))
            }
        }
    }

    @Test fun gm_a11y_005_pauseCardWaitsForever() = waitsAnHour(
        Fixtures.pauseDuringCall,
        listOf("Hold on a minute.", "Call Sarah first", "I'm fine, continue", "Go to my home screen"),
    )

    @Test fun gm_a11y_005_remoteControlCardWaitsForever() = waitsAnHour(
        Fixtures.remote,
        listOf("Call Sarah", "Remove AnyDesk", "I trust this app"),
    )

    @Test fun gm_a11y_005_codeEntryWaitsForever() = waitsAnHour(
        CardRequest(CardKind.LOCKED, "com.android.settings", "Settings"),
        listOf("Settings is locked", "Unlock", "Go to my home screen"),
    )

    /** The wait after too many tries is a clock time, and it stays the same words while time passes. */
    @Test fun gm_a11y_005_lockCodeWaitIsAClockTimeNotACountdown() {
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(Fixtures.zone))
        try {
            val retryAt = Fixtures.now.plusSeconds(30).toInstant().toEpochMilli()
            rule.setContent { GuardianTheme { UnlockForm(prompt = "Your code", tryUnlock = { UnlockResult.Wait(retryAt) }, onUnlocked = {}) } }
            rule.onNode(hasContentDescription("Lock code") and hasSetTextAction()).performTextInput("1111")
            rule.onNodeWithText("Unlock").performClick()
            val message = "That's too many tries. You can try again at 9:41 AM."
            rule.onNodeWithText(message).assertExists()
            val before = rule.allText()
            assertThat(before.joinToString(" ")).doesNotContainMatch("(?i)\\bseconds?\\b|\\b0:\\d\\d\\b")
            anHourPasses()
            assertWithMessage("nothing on the page counts down").that(rule.allText()).isEqualTo(before)
        } finally {
            TimeZone.setDefault(zone)
        }
    }

    /**
     * The 10-minute unlock window is the one timer, and it ends quietly: the phone is locked
     * again the next time something asks, with no card, page or notification of its own.
     */
    @Test fun gm_a11y_005_unlockWindowEndsQuietly() = runBlocking<Unit> {
        val (store, scope) = UiChecks.openStore(app, tmp.newFolder("files"))
        try {
            var now = Fixtures.now.toInstant().toEpochMilli()
            val lock = LockManager(app, store) { now }
            store.update { it.copy(onboardingDone = true, config = it.config.copy(lock = it.config.lock.copy(level = LockLevel.GUIDED))) }
            lock.setCode("2580")
            assertThat(lock.tryUnlock("2580")).isEqualTo(UnlockResult.Unlocked)
            val latest = store.update { it }
            store.state.first { it == latest }
            assertThat(LockPolicy.UNLOCK_MILLIS).isEqualTo(10 * 60_000L)
            val notifications = app.getSystemService(NotificationManager::class.java)
            val posted = shadowOf(notifications).allNotifications.size
            while (shadowOf(app).nextStartedActivity != null) Unit

            now += LockPolicy.UNLOCK_MILLIS - 1_000
            assertThat(lock.isUnlocked()).isTrue()
            now += 2_000
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(11))
            assertThat(lock.isUnlocked()).isFalse()
            assertWithMessage("nothing opened when the window ended").that(shadowOf(app).nextStartedActivity).isNull()
            assertWithMessage("nothing posted when the window ended").that(shadowOf(notifications).allNotifications.size).isEqualTo(posted)
        } finally {
            UiChecks.close(scope)
        }
    }
}
