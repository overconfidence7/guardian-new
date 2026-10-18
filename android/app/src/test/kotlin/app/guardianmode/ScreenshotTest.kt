package app.guardianmode

import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.guardianmode.core.DisplaySettings
import app.guardianmode.core.ThemePreference
import app.guardianmode.core.Transparency
import app.guardianmode.data.HelperInfo
import app.guardianmode.ui.cards.CardActions
import app.guardianmode.ui.cards.CardContent
import app.guardianmode.ui.home.HomeActions
import app.guardianmode.ui.home.HomeModel
import app.guardianmode.ui.home.HomeScreen
import app.guardianmode.ui.home.TileUi
import app.guardianmode.ui.onboarding.ConsentPages
import app.guardianmode.ui.onboarding.OnboardingActions
import app.guardianmode.ui.onboarding.OnboardingFlow
import app.guardianmode.ui.onboarding.Stage
import app.guardianmode.ui.theme.GuardianTheme
import app.guardianmode.ui.transparency.TransparencyActions
import app.guardianmode.ui.transparency.TransparencyScreen
import app.guardianmode.ui.transparency.TransparencyUi
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Visual baselines for every screen the parent sees, at the sizes and settings that
 * matter for older eyes. Recorded with `recordRoborazziDebug`, verified on every push.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class ScreenshotTest {

    private fun shot(name: String, display: DisplaySettings = DisplaySettings(), content: @Composable () -> Unit) {
        captureRoboImage("src/test/screenshots/$name.png") {
            GuardianTheme(display) { content() }
        }
    }

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

    @Test fun homeDefault() = shot("home_default") {
        HomeScreen(HomeModel.build(Fixtures.state, Fixtures.now), Fixtures.now, home)
    }

    @Test fun homeLargestText() = shot("home_font_2x", DisplaySettings(fontScale = 2.0f)) {
        HomeScreen(HomeModel.build(Fixtures.state, Fixtures.now), Fixtures.now, home)
    }

    @Test fun homeDark() = shot("home_dark", DisplaySettings(theme = ThemePreference.DARK)) {
        HomeScreen(HomeModel.build(Fixtures.state, Fixtures.now), Fixtures.now, home)
    }

    @Test fun homeHighContrast() = shot("home_high_contrast", DisplaySettings(highContrast = true)) {
        HomeScreen(HomeModel.build(Fixtures.state, Fixtures.now), Fixtures.now, home)
    }

    @Test fun homeHelperRequest() = shot("home_helper_request") {
        val state = Fixtures.state.copy(helpers = Fixtures.state.helpers + HelperInfo("u3", "Rick", status = "pending"))
        HomeScreen(HomeModel.build(state, Fixtures.now), Fixtures.now, home)
    }

    @Test fun pauseCard() = shot("card_pause") { CardContent(Fixtures.pause, Fixtures.config, card) }

    @Test fun pauseCardDuringCallLargeText() = shot("card_pause_call_font_2x", DisplaySettings(fontScale = 2.0f)) {
        CardContent(Fixtures.pauseDuringCall, Fixtures.config, card)
    }

    @Test fun remoteAccessCard() = shot("card_remote_access") { CardContent(Fixtures.remote, Fixtures.config, card) }

    @Test fun safeWordCard() = shot("card_safe_word") { CardContent(Fixtures.safeWord, Fixtures.config, card) }

    @Test fun reminderCard() = shot("card_reminder") { CardContent(Fixtures.reminder, Fixtures.config, card) }

    @Test fun transparency() = shot("transparency") {
        TransparencyScreen(
            TransparencyUi(
                items = Transparency.items(Fixtures.config),
                helpers = Fixtures.state.helpers,
                shared = Fixtures.state.sharedEvents,
                changes = emptyList(),
                paused = false,
                pausedUntil = null,
                paired = true,
            ),
            object : TransparencyActions {
                override fun onToggle(key: String, enabled: Boolean) = Unit
                override fun onPauseHour() = Unit
                override fun onResume() = Unit
                override fun onRemoveHelper(helper: HelperInfo) = Unit
                override fun onStopUsing() = Unit
                override fun onBack() = Unit
            },
            Fixtures.zone,
        )
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

    private fun onboardingShot(name: String, stage: Stage, page: Int = 0, accepted: Boolean = false, code: String = "") =
        shot(name) {
            OnboardingFlow(
                stage = stage, code = code, preview = app.guardianmode.cloud.InvitePreview("The Parkers", "Margaret", "Sarah"),
                busy = false, error = null, page = ConsentPages.all[page], pageNumber = page + 1, pageCount = ConsentPages.all.size,
                accepted = accepted, refreshKey = 0, granted = { false }, actions = onboarding,
            )
        }

    @Test fun onboardingWelcome() = onboardingShot("onboarding_welcome", Stage.WELCOME)
    @Test fun onboardingCode() = onboardingShot("onboarding_code", Stage.CODE, code = "K7P")
    @Test fun onboardingConfirm() = onboardingShot("onboarding_confirm", Stage.CONFIRM)
    @Test fun onboardingConsentPause() = onboardingShot("onboarding_consent_pause", Stage.CONSENT, page = 1)
    @Test fun onboardingConsentChecklist() = onboardingShot("onboarding_consent_checklist", Stage.CONSENT, page = 2, accepted = true)
    @Test fun onboardingDone() = onboardingShot("onboarding_done", Stage.DONE)
}
