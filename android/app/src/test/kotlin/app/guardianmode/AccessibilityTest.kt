package app.guardianmode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.guardianmode.UiChecks.clippedText
import app.guardianmode.UiChecks.offScreenSideways
import app.guardianmode.UiChecks.smallTargets
import app.guardianmode.cloud.InvitePreview
import app.guardianmode.core.DisplaySettings
import app.guardianmode.core.LockLevel
import app.guardianmode.core.ReminderSchedule
import app.guardianmode.core.Transparency
import app.guardianmode.protection.Capability
import app.guardianmode.protection.UnlockResult
import app.guardianmode.ui.cards.CardActions
import app.guardianmode.ui.cards.CardContent
import app.guardianmode.ui.cards.CardKind
import app.guardianmode.ui.cards.CardRequest
import app.guardianmode.ui.home.AllAppsScreen
import app.guardianmode.ui.home.AppEntry
import app.guardianmode.ui.home.HomeActions
import app.guardianmode.ui.home.HomeModel
import app.guardianmode.ui.home.HomeScreen
import app.guardianmode.ui.home.PhotosScreen
import app.guardianmode.ui.home.RemindersScreen
import app.guardianmode.ui.home.TileUi
import app.guardianmode.ui.lock.LockSettingsScreen
import app.guardianmode.ui.lock.UnlockScreen
import app.guardianmode.ui.onboarding.ConsentPages
import app.guardianmode.ui.onboarding.OnboardingActions
import app.guardianmode.ui.onboarding.OnboardingFlow
import app.guardianmode.ui.onboarding.Stage
import app.guardianmode.ui.theme.GuardianTheme
import app.guardianmode.ui.transparency.TransparencyActions
import app.guardianmode.ui.transparency.TransparencyScreen
import app.guardianmode.ui.transparency.TransparencyUi
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** qa/antigravity/cases/15-accessibility.md, over every parent-facing screen that renders without Android services. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class AccessibilityTest {

    @get:Rule val rule = createComposeRule()

    private val home = object : HomeActions {
        override fun onTile(tile: TileUi) = Unit
        override fun onPhoto() = Unit
        override fun onShield() = Unit
        override fun onAllApps() = Unit
        override fun onReminders() = Unit
        override fun onHelperDecision(uid: String, approve: Boolean) = Unit
    }
    private val card = object : CardActions {
        override fun callFamily(phone: String) = Unit
        override fun continueAnyway() = Unit
        override fun goHome() = Unit
        override fun removeApp() = Unit
        override fun reminderDone() = Unit
        override fun snooze() = Unit
        override fun dismiss() = Unit
    }
    private val transparency = object : TransparencyActions {
        override fun onToggle(key: String, enabled: Boolean) = Unit
        override fun onPauseHour() = Unit
        override fun onResume() = Unit
        override fun onRemoveHelper(helper: app.guardianmode.data.HelperInfo) = Unit
        override fun onStopUsing() = Unit
        override fun onBack() = Unit
    }
    private val onboarding = object : OnboardingActions {
        override fun start() = Unit
        override fun setupAlone() = Unit
        override fun onCodeChange(value: String) = Unit
        override fun scan() = Unit
        override fun onScanned(raw: String) = Unit
        override fun submitCode() = Unit
        override fun confirm() = Unit
        override fun decline() = Unit
        override fun turnOn() = Unit
        override fun requestNextMissing() = Unit
        override fun notNow() = Unit
        override fun nextPage() = Unit
        override fun finishSetup() = Unit
    }

    @Composable
    private fun onboardingScreen(stage: Stage, page: Int = 0, accepted: Boolean = false, granted: (Capability) -> Boolean = { false }) =
        OnboardingFlow(
            stage = stage, code = "K7P", preview = InvitePreview("The Parkers", "Margaret", "Sarah"),
            busy = false, error = null, page = ConsentPages.all[page], pageNumber = page + 1, pageCount = ConsentPages.all.size,
            accepted = accepted, refreshKey = 0, granted = granted, actions = onboarding,
        )

    /** Every parent-facing screen, by name. */
    private fun s(name: String, content: @Composable () -> Unit): Pair<String, @Composable () -> Unit> = name to content

    private val screens: List<Pair<String, @Composable () -> Unit>> = listOf(
        s("role") { onboardingScreen(Stage.ROLE) },
        s("helper intro") { onboardingScreen(Stage.HELPER) },
        s("welcome") { onboardingScreen(Stage.WELCOME) },
        s("setup code") { onboardingScreen(Stage.CODE) },
        s("confirm helper") { onboardingScreen(Stage.CONFIRM) },
        s("lock level") { onboardingScreen(Stage.LOCK_LEVEL) },
        s("set code") { onboardingScreen(Stage.SET_CODE) },
        s("done") { onboardingScreen(Stage.DONE) },
    ) + ConsentPages.all.indices.flatMap { i ->
        listOf(
            s("consent ${i + 1}") { onboardingScreen(Stage.CONSENT, page = i) },
            s("consent ${i + 1} checklist") { onboardingScreen(Stage.CONSENT, page = i, accepted = true) },
        )
    } + listOf(
        s("home solo") { HomeScreen(HomeModel.build(UiChecks.soloState(), Fixtures.now), Fixtures.now, home) },
        s("home family") { HomeScreen(HomeModel.build(Fixtures.state, Fixtures.now), Fixtures.now, home) },
        s("all my apps") { AllAppsScreen(listOf(AppEntry("com.example.notes", "Notes")), {}, {}, {}) },
        s("family photos") { PhotosScreen(emptyList(), Fixtures.now, onBack = {}, onAdd = {}) },
        s("today's reminders") {
            RemindersScreen(ReminderSchedule.upcomingToday(Fixtures.config.reminders, Fixtures.now.toLocalDate().atStartOfDay(Fixtures.zone)), emptySet(), {})
        },
        s("what my family can see") {
            TransparencyScreen(
                TransparencyUi(Transparency.items(Fixtures.config), Fixtures.state.helpers, Fixtures.state.sharedEvents, emptyList(), false, null, true, LockLevel.GUIDED, true),
                transparency, Fixtures.zone,
            )
        },
        s("phone lock") { LockSettingsScreen(LockLevel.GUIDED, hasCode = true, onLevel = {}, onSetCode = {}, onLockNow = {}, onBack = {}) },
        s("unlock") { UnlockScreen(tryUnlock = { UnlockResult.Wrong }, onUnlocked = {}, onBack = {}) },
        s("pause card") { CardContent(Fixtures.pauseDuringCall, Fixtures.config, card) },
        s("remote-control card") { CardContent(Fixtures.remote, Fixtures.config, card) },
        s("safe word card") { CardContent(Fixtures.safeWord, Fixtures.config, card) },
        s("reminder card") { CardContent(Fixtures.reminder, Fixtures.config, card) },
        s("locked app card") { CardContent(CardRequest(CardKind.LOCKED, "com.android.settings", "Settings"), Fixtures.config, card) },
    )

    /** Renders each screen in turn and collects what [check] reports for it. */
    private fun everyScreen(display: DisplaySettings = DisplaySettings(), check: (String) -> List<String>): List<String> {
        var current by mutableStateOf(screens.first().second)
        rule.setContent { GuardianTheme(display) { current() } }
        return screens.flatMap { (name, content) ->
            current = content
            rule.waitForIdle()
            check(name).map { "$name: $it" }
        }
    }

    @Test fun gm_a11y_001_everyTouchTargetIsAtLeast48dp() {
        val small = everyScreen { rule.smallTargets(rule.density, 48f) }
        assertThat(small).isEmpty()
    }

    /** docs/SPEC.md bar 8 asks for 64 dp; text buttons and the shield chip fall short. */
    @Category(KnownBug::class)
    @Test fun gm_a11y_001_everyTouchTargetIsAtLeast64dpAsTheSpecAsks() {
        val small = everyScreen { rule.smallTargets(rule.density, 64f) }
        assertThat(small).isEmpty()
    }

    @Test fun gm_a11y_003_largestSystemTextClipsNothing() {
        RuntimeEnvironment.setFontScale(2.0f)
        val width = 360f * 2 // xhdpi
        val problems = everyScreen {
            rule.clippedText() + rule.offScreenSideways(width).map { "off screen: $it" }
        }
        assertThat(problems).isEmpty()
    }

    @Test fun gm_a11y_003_gm_pause_045_pauseCardButtonsReachableAtLargestText() {
        RuntimeEnvironment.setFontScale(2.0f)
        rule.setContent { GuardianTheme(DisplaySettings(fontScale = 2.0f)) { CardContent(Fixtures.pauseDuringCall, Fixtures.config, card) } }
        listOf("Call Sarah first", "I'm fine, continue", "Go to my home screen").forEach {
            rule.onNode(hasText(it) and hasClickAction()).performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun gm_a11y_007_checklistUsesWordsNotJustColor() {
        rule.setContent { GuardianTheme { onboardingScreen(Stage.CONSENT, page = 0, accepted = true, granted = { it == Capability.HOME_APP }) } }
        rule.onAllNodesWithContentDescription("Done").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Not yet").assertCountEquals(1)
    }
}
