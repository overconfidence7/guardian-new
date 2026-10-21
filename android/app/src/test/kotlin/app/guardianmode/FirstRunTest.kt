package app.guardianmode

import android.provider.Settings
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.guardianmode.UiChecks.allText
import app.guardianmode.core.LockLevel
import app.guardianmode.protection.Features
import app.guardianmode.ui.onboarding.ConsentPages
import app.guardianmode.ui.onboarding.OnboardingActivity
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Cases from qa/antigravity/cases/01-install-and-first-run.md, 02-setup-and-consent.md and the
 * setup half of 03-lock-levels-and-code.md, driven through the real OnboardingActivity. Android's
 * own permission screens never appear under Robolectric; the tests check that Guardian asks for
 * them (or doesn't) at the right moment.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class FirstRunTest {

    @get:Rule val rule = createAndroidComposeRule<OnboardingActivity>()

    private val store get() = (rule.activity.application as GuardianApp).container.store

    private fun tap(text: String) {
        rule.onNodeWithText(text).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun systemBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    /** Waits briefly for [text] (some steps finish on a background write), then asserts it's there. */
    private fun shown(text: String) {
        val found = runCatching {
            rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        }.isSuccess
        assertWithMessage("\"$text\" on screen; showing:\n" + rule.allText().joinToString("\n")).that(found).isTrue()
    }

    private fun codeField() = rule.onNode(hasContentDescription("Setup code") and hasSetTextAction())

    private fun editable(value: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value))

    /** What was typed into a password field (its EditableText is dots). */
    private fun typed(value: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString(value))

    /** Nothing has been asked of Android: no permission dialog, no Settings screen. */
    private fun assertNoAndroidPrompt() {
        val shadow = shadowOf(rule.activity)
        assertThat(shadow.lastRequestedPermission).isNull()
        assertThat(shadow.peekNextStartedActivityForResult()).isNull()
        assertThat(shadow.peekNextStartedActivity()).isNull()
    }

    private fun toConsent() {
        tap("Make this phone simpler")
        tap("Set up on my own for now")
    }

    private fun awaitConsent(key: String, value: Boolean) =
        rule.waitUntil(5_000) { store.current.consent[key] == value }

    @Test fun gm_inst_002_roleChoiceSaysWhatEachPathIsFor() {
        shown("How will this phone be used?")
        shown("Guardian Mode goes on the phone that should be simpler. Family who help can use it on their own phone too.")
        shown("Make this phone simpler")
        shown("For the person who will use this phone every day. You can set it up for them.")
        shown("Help someone else's phone")
        shown("For family who help from their own phone.")
        val words = rule.allText().joinToString(" ").lowercase()
        listOf("monitor", "track", "watch").forEach { assertThat(words).doesNotContain(it) }
    }

    @Test fun gm_inst_003_helperPathExplainsAndBackReturns() {
        tap("Help someone else's phone")
        shown("Helping from your phone")
        // The box says linking isn't ready and points back to "Make this phone simpler" (exact wording has moved since the case was written).
        rule.onNode(hasText("Linking phones isn't", substring = true) and hasText("\"Make this phone simpler\"", substring = true)).assertExists()
        // The only button on the page is Back.
        assertThat(rule.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes().map { UiChecks.label(it) })
            .containsExactly("Back")
        tap("Back")
        shown("How will this phone be used?")

        tap("Help someone else's phone")
        systemBack()
        shown("How will this phone be used?")
        assertThat(rule.activity.isFinishing).isFalse()
    }

    @Test fun gm_inst_004_backOnRoleScreenClosesSetup() {
        systemBack()
        assertThat(rule.activity.isFinishing).isTrue()
    }

    @Test fun gm_inst_005_welcomeHasNoDarkPatterns() {
        tap("Make this phone simpler")
        shown("Make your phone easier to use.")
        shown("Set up with my family")
        shown("Set up on my own for now")
        val words = rule.allText().joinToString(" ")
        assertThat(words).doesNotContainMatch("(?i)\\bskip\\b")
        // No checkbox or switch to be pre-ticked.
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertCountEquals(0)
    }

    @Test fun gm_inst_007_setupCodeFieldCleansWhatIsTyped() {
        tap("Make this phone simpler")
        tap("Set up with my family")
        shown("Enter your setup code")
        codeField().assertIsFocused() // the keyboard opens by itself

        codeField().performTextInput("abc234")
        codeField().assert(editable("ABC234"))

        codeField().performTextReplacement("IO01L")
        codeField().assert(editable(""))

        codeField().performTextReplacement("ab-c 2 34")
        codeField().assert(editable("ABC234"))

        codeField().performTextReplacement("ABCDEFGH")
        codeField().assert(editable("ABCDEF"))

        // Done with fewer than 6 characters does nothing: same screen, no spinner, no message.
        codeField().performTextReplacement("ABC23")
        codeField().performImeAction()
        rule.waitForIdle()
        shown("Enter your setup code")
        shown("Continue")
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        assertThat(rule.allText().joinToString(" ")).doesNotContain("didn't work")
    }

    @Test fun gm_inst_008_scanWithoutCameraOffersTyping() {
        tap("Make this phone simpler")
        tap("Set up with my family")
        tap("Scan the code instead")
        // Robolectric never grants the camera, which is the "denied" branch of the case.
        shown("Guardian needs the camera to read the code. You can type the code instead.")
        tap("Type the code instead")
        shown("Enter your setup code")
    }

    @Test fun gm_inst_009_backWalksTheFirstRunScreensInOrder() {
        tap("Make this phone simpler")
        tap("Set up with my family")
        tap("Scan the code instead")
        shown("Point the camera at the code")
        systemBack(); shown("Enter your setup code")
        systemBack(); shown("Make your phone easier to use.")
        systemBack(); shown("How will this phone be used?")
        assertThat(rule.activity.isFinishing).isFalse()
        systemBack()
        assertThat(rule.activity.isFinishing).isTrue()
    }

    @Test fun gm_cons_001_everyPageDisclosesBeforeAnyAndroidPrompt() {
        toConsent()
        ConsentPages.all.forEachIndexed { i, page ->
            val n = i + 1
            shown("Step $n of ${ConsentPages.all.size}")
            rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(n.toFloat() / ConsentPages.all.size, 0f..1f)))
                .assertExists()
            shown(page.title)
            shown(page.intro)
            shown("Your family will see")
            shown(page.familySees)
            shown("Never shared")
            shown(page.neverShared)
            shown("Turn on")
            shown("Not now")
            assertNoAndroidPrompt()
            tap("Not now")
        }
        shown("How locked down should this phone be?")
        assertNoAndroidPrompt()
    }

    @Test fun gm_cons_002_turnOnShowsChecklistThenOpensAndroidForTheFirstMissingItem() {
        toConsent()
        tap("Turn on")
        shown("Android will ask you to allow a few things.")
        shown("Guardian is the home screen")
        shown("Can call family with one tap")
        rule.onAllNodesWithContentDescription("Not yet").assertCountEquals(2)
        shown("On the next screen")
        shown("Choose Guardian Mode, then tap \"Set as default\".")
        shown("Continue")
        shown("Skip for now")
        val opened = shadowOf(rule.activity).nextStartedActivityForResult
        assertThat(opened).isNotNull()
        assertThat(opened.intent.action).isAnyOf("android.app.role.action.REQUEST_ROLE", Settings.ACTION_HOME_SETTINGS)
        awaitConsent(Features.HOME, true)
    }

    @Test fun gm_cons_008_dailyCheckInAsksAndroidForNothing() {
        toConsent()
        repeat(3) { tap("Not now") }
        shown(ConsentPages.all[3].title)
        tap("Turn on")
        shown("All set")
        shown("Next")
        assertNoAndroidPrompt()
        tap("Next")
        shown(ConsentPages.all[4].title)
        assertNoAndroidPrompt()
    }

    @Test fun gm_cons_011_notNowAndSkipForNowReallySkip() {
        toConsent()
        tap("Turn on") // page 1
        tap("Skip for now")
        tap("Not now") // page 2
        tap("Turn on") // page 3
        tap("Skip for now")
        repeat(3) { tap("Not now") }
        shown("How locked down should this phone be?")
        rule.waitUntil(5_000) { store.current.consent.size == ConsentPages.all.size }
        val consent = store.current.consent
        assertThat(consent[Features.HOME]).isTrue()
        assertThat(consent[Features.PAUSE]).isFalse()
        assertThat(consent[Features.CALLS]).isTrue()
        assertThat(consent[Features.CHECK_IN]).isFalse()
        // Guardian asked, but nothing was granted on the parent's behalf.
        listOf(
            android.Manifest.permission.CALL_PHONE,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_CONTACTS,
        ).forEach {
            assertThat(androidx.core.content.ContextCompat.checkSelfPermission(rule.activity, it))
                .isEqualTo(android.content.pm.PackageManager.PERMISSION_DENIED)
        }
    }

    @Test fun gm_cons_014_backInsideConsentPagesKeepsEachAnswer() {
        toConsent()
        tap("Turn on"); tap("Skip for now") // page 1: yes
        tap("Not now") // page 2: no
        tap("Turn on"); tap("Skip for now") // page 3: yes
        shown(ConsentPages.all[3].title)
        awaitConsent(Features.CALLS, true)

        systemBack()
        shown(ConsentPages.all[2].title)
        shown("Skip for now")
        rule.onAllNodesWithText("Turn on").assertCountEquals(0)

        systemBack()
        shown(ConsentPages.all[1].title)
        shown("Turn on")

        systemBack()
        shown(ConsentPages.all[0].title)
        shown("Skip for now")
        rule.onAllNodesWithText("Turn on").assertCountEquals(0)

        systemBack()
        shown("Make your phone easier to use.")

        // Forward again: the answers are still there.
        tap("Set up on my own for now")
        shown(ConsentPages.all[0].title)
        shown("Skip for now")
        tap("Skip for now")
        shown(ConsentPages.all[1].title)
        shown("Turn on")
        tap("Not now")
        shown(ConsentPages.all[2].title)
        shown("Skip for now")
    }

    @Test fun gm_cons_017_consentWordingHasNoAndroidJargon() {
        val jargon = Regex("(?i)\\b(overlay|role|optimi[sz]ation|permission|default app)\\b")
        val lines = ConsentPages.all.flatMap { listOf(it.title, it.intro, it.familySees, it.neverShared) } +
            app.guardianmode.protection.Capability.entries.map { it.plainName }
        assertThat(lines.filter { jargon.containsMatchIn(it) }).isEmpty()
    }

    @Test fun gm_lock_001_gm_lock_002_gm_lock_003_gm_lock_004_lockLevelAndCodeDuringSetup() {
        toConsent()
        repeat(ConsentPages.all.size) { tap("Not now") }

        // GM-LOCK-001: the choice, Guided preselected, radio buttons.
        shown("How locked down should this phone be?")
        shown("You can change this later with the code.")
        fun level(name: String) = rule.onNode(
            hasText(name) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
        )
        level("Simple").assertIsNotSelected()
        level("Guided").assertIsSelected()
        level("Locked").assertIsNotSelected()
        level("Locked").assert(hasText("Calls always work, including 911.", substring = true))
        level("Locked").performScrollTo().performClick()
        level("Locked").assertIsSelected()
        level("Guided").assertIsNotSelected()
        tap("Next")
        rule.waitUntil(5_000) { store.current.config.lock.level == LockLevel.LOCKED }

        // GM-LOCK-002: the code rules.
        shown("Pick a lock code")
        val first = rule.onNode(hasContentDescription("New code") and hasSetTextAction())
        first.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)) // shown as dots
        first.performTextInput("123")
        tap("Next")
        shown("The code needs at least 4 numbers.")
        first.performTextReplacement("25ab80")
        first.assert(typed("2580")) // letters can't be typed
        first.performTextReplacement("123456789")
        first.assert(typed("12345678")) // stops at 8
        first.performTextReplacement("2580")
        tap("Next")
        shown("Type the same code again.")
        val second = rule.onNode(hasContentDescription("Same code again") and hasSetTextAction())
        second.performTextInput("2581")
        tap("Save this code")
        shown("Those didn't match. Start again.")
        shown("Pick a code of 4 to 8 numbers.")
        rule.onNode(hasContentDescription("New code") and hasSetTextAction()).assert(typed(""))
        rule.onNode(hasContentDescription("New code") and hasSetTextAction()).performTextInput("2580")
        tap("Next")
        rule.onNode(hasContentDescription("Same code again") and hasSetTextAction()).assert(typed(""))
        rule.onNode(hasContentDescription("Same code again") and hasSetTextAction()).performTextInput("2580")
        tap("Save this code")
        shown("You're all set.")

        // GM-LOCK-003: only a salted hash is kept, and the code is nowhere in the saved file.
        rule.waitUntil(5_000) { store.current.lockCodeHash.isNotEmpty() }
        val state = store.current
        assertThat(state.lockCodeHash).matches("[0-9a-f]{64}")
        assertThat(java.util.UUID.fromString(state.lockCodeSalt).toString()).isEqualTo(state.lockCodeSalt)
        val saved = File(rule.activity.filesDir, "datastore/guardian_state.json").readText()
        assertThat(saved).contains(state.lockCodeHash)
        assertThat(saved.replace(state.lockCodeHash, "").replace(state.lockCodeSalt, "")).doesNotContain("2580")

        // GM-LOCK-004: Back from "You're all set" returns to the level choice, since a code exists.
        systemBack()
        shown("How locked down should this phone be?")
        tap("Next")
        shown("Pick a lock code")
    }
}
