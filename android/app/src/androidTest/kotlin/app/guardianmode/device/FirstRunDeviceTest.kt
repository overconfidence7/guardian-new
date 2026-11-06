package app.guardianmode.device

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.guardianmode.support.DeviceRule
import app.guardianmode.ui.onboarding.OnboardingActivity
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * qa/antigravity/cases/01-install-and-first-run.md on a device, where the cloud is really
 * called. The pages are read through Compose's own test API in Guardian's process, not
 * UiAutomator, which on API 33 and 36 can read setup's window as empty.
 */
@RunWith(AndroidJUnit4::class)
class FirstRunDeviceTest {
    private val rule = DeviceRule()
    private val compose = createEmptyComposeRule()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(rule).around(compose)

    @After fun forgetSetup() = OnboardingActivity.clearProgress(rule.context)

    private fun allText(): List<String> =
        compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun showing(text: String) =
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString(text))), useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    private fun waitFor(text: String, timeoutMs: Long = 10_000) {
        val found = runCatching { compose.waitUntil(timeoutMs) { showing(text) } }.isSuccess
        assertWithMessage("\"$text\" on screen; showing: ${allText()}").that(found).isTrue()
    }

    private fun tap(text: String) {
        compose.onNodeWithText(text).performScrollTo().performClick()
        compose.waitForIdle()
    }

    /**
     * GM-INST-006: "Set up with my family" with a made-up code, in a build whose cloud isn't
     * there (the device job points the app at Firebase emulators that aren't running). After
     * a short wait it says one of the plain messages, with no raw error, well within 30 s, and
     * the parent can try again or go back.
     */
    @Test fun gm_inst_006_setUpWithMyFamilyFailsKindlyWithoutTheCloud() {
        OnboardingActivity.clearProgress(rule.context)
        assertThat(rule.state.pairing).isNull()
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            waitFor("How will this phone be used?")
            tap("Make this phone simpler")
            tap("Set up with my family")
            waitFor("Enter your setup code")
            compose.onNode(hasContentDescription("Setup code") and hasSetTextAction()).performTextInput("ABC234")
            val started = SystemClock.elapsedRealtime()
            tap("Continue")
            val busy = !showing("Continue")
            val appeared = runCatching { compose.waitUntil(60_000) { KIND.any(::showing) } }.isSuccess
            val tookMs = SystemClock.elapsedRealtime() - started
            val text = allText()
            val message = KIND.firstOrNull(::showing)
            Log.i(TAG, "GM-INST-006: spinner shown=$busy; after ${tookMs} ms: \"$message\"; on screen: $text")
            assertWithMessage("a plain message after Continue; on screen: $text").that(appeared).isTrue()
            assertWithMessage("time to the message").that(tookMs).isLessThan(30_000L)
            val words = text.joinToString(" ")
            assertWithMessage("no raw error").that(words).doesNotContainMatch("(?i)exception|firebase|10\\.0\\.2\\.2|unavailable|internal|deadline|null|error code")
            scenario.onActivity { assertThat(it.isFinishing).isFalse() }

            // The parent can try again: the button is back and the code is still there.
            waitFor("Continue")
            waitFor("Scan the code instead")
            assertThat(rule.state.pairing).isNull()

            // And go back.
            Espresso.closeSoftKeyboard()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor("Make your phone easier to use.")
            assertWithMessage("the message goes away on the way back").that(KIND.none(::showing)).isTrue()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor("How will this phone be used?")
        }
    }

    private companion object {
        const val TAG = "FirstRunDeviceTest"

        /** The messages GM-INST-006 accepts, any of which is kind. */
        val KIND = listOf(
            "That code didn't work. Double check it with your family member.",
            "The phone isn't connected to the internet. Connect to Wi-Fi and try again.",
            "That didn't work. Try again in a minute.",
        )
    }
}
