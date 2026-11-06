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
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.geometry.Rect
import android.graphics.Bitmap
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.guardianmode.UiChecks.clippedText
import app.guardianmode.UiChecks.offScreenSideways
import app.guardianmode.UiChecks.smallTargets
import app.guardianmode.cloud.InvitePreview
import app.guardianmode.core.DisplaySettings
import app.guardianmode.core.ThemePreference
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
import androidx.compose.ui.unit.dp
import org.junit.Test
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

    /** docs/SPEC.md bar 8 asks for 64 dp. */
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

    /**
     * GM-A11Y-004: every piece of text on every screen, sampled from the rendered pixels in light,
     * dark and high contrast. For each text node the commonest colour inside its bounds is the
     * background and the colour (of a few pixels at least, so not edge smoothing) that differs
     * most from it is the text. Large text is WCAG's: 24 sp, or 18.66 sp bold. Disabled controls
     * are exempt, as in WCAG. The screen is tall so whole pages are in view.
     */
    @Config(qualifiers = "w360dp-h2400dp-xhdpi")
    @Test fun gm_a11y_004_textContrastInLightDarkAndHighContrast() {
        data class Bar(val name: String, val display: DisplaySettings, val normal: Double, val large: Double)
        val bars = listOf(
            Bar("light", DisplaySettings(theme = ThemePreference.LIGHT), 4.5, 3.0),
            Bar("dark", DisplaySettings(theme = ThemePreference.DARK), 4.5, 3.0),
            Bar("high contrast", DisplaySettings(highContrast = true), 7.0, 7.0),
        )
        // Initials on every avatar colour (the fixtures' two names only reach two of them).
        val avatars = s("avatars") {
            androidx.compose.foundation.layout.Column {
                ('A'..'L').forEach { app.guardianmode.ui.components.Avatar(it.toString(), null, 56.dp) }
            }
        }
        var display by mutableStateOf(bars.first().display)
        var current by mutableStateOf(screens.first().second)
        rule.setContent { GuardianTheme(display) { current() } }
        var checked = 0
        val failures = bars.flatMap { bar ->
            display = bar.display
            (screens + avatars).flatMap { (name, content) ->
                current = content
                rule.waitForIdle()
                val shot = rule.onRoot().captureToImage().asAndroidBitmap()
                rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
                    .fetchSemanticsNodes(atLeastOneRootRequired = false)
                    .filterNot { disabled(it) }
                    .mapNotNull { node ->
                        val sample = Contrast.sample(shot, node.boundsInRoot) ?: return@mapNotNull null
                        checked++
                        val need = if (isLarge(node)) bar.large else bar.normal
                        if (sample.ratio + 0.005 >= need) null
                        else "${bar.name} / $name / \"${UiChecks.label(node).take(40)}\": text ${Contrast.hex(sample.text)} on ${Contrast.hex(sample.background)} is " +
                            "%.2f:1, needs %.1f:1".format(sample.ratio, need)
                    }
            }
        }
        assertThat(checked).isGreaterThan(bars.size * screens.size * 2)
        assertThat(failures).isEmpty()
    }

    private fun disabled(node: SemanticsNode): Boolean =
        generateSequence(node) { it.parent }.any { it.config.contains(SemanticsProperties.Disabled) }

    private fun isLarge(node: SemanticsNode): Boolean {
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        val style = results.firstOrNull()?.layoutInput?.style ?: return false
        val sp = style.fontSize.value
        val bold = (style.fontWeight ?: FontWeight.Normal) >= FontWeight.Bold
        return sp >= 24f || (bold && sp >= 18.66f)
    }

    @Test fun gm_a11y_007_checklistUsesWordsNotJustColor() {
        rule.setContent { GuardianTheme { onboardingScreen(Stage.CONSENT, page = 0, accepted = true, granted = { it == Capability.HOME_APP }) } }
        rule.onAllNodesWithContentDescription("Done").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Not yet").assertCountEquals(1)
    }
}

/** WCAG contrast, measured from rendered pixels. */
object Contrast {
    data class Sample(val text: Int, val background: Int, val ratio: Double)

    private fun channel(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }

    fun luminance(argb: Int): Double =
        0.2126 * channel((argb shr 16) and 0xFF) + 0.7152 * channel((argb shr 8) and 0xFF) + 0.0722 * channel(argb and 0xFF)

    fun ratio(a: Int, b: Int): Double {
        val (hi, lo) = luminance(a).let { la -> luminance(b).let { lb -> maxOf(la, lb) to minOf(la, lb) } }
        return (hi + 0.05) / (lo + 0.05)
    }

    fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    /**
     * The background is the commonest colour in [bounds]; the text is the colour, covering at
     * least [minPixels] pixels, that differs most from it. Null when the area is too small or
     * holds a single colour (nothing drawn, or text scrolled out of view).
     */
    fun sample(shot: Bitmap, bounds: Rect, minPixels: Int = 4): Sample? {
        val left = bounds.left.toInt().coerceIn(0, shot.width)
        val top = bounds.top.toInt().coerceIn(0, shot.height)
        val right = bounds.right.toInt().coerceIn(0, shot.width)
        val bottom = bounds.bottom.toInt().coerceIn(0, shot.height)
        val w = right - left
        val h = bottom - top
        if (w < 4 || h < 4) return null
        val pixels = IntArray(w * h)
        shot.getPixels(pixels, 0, w, left, top, w, h)
        val counts = HashMap<Int, Int>()
        for (p in pixels) counts.merge(p or 0xFF000000.toInt(), 1, Int::plus)
        val background = counts.maxByOrNull { it.value }!!.key
        val text = counts.filter { it.value >= minPixels && it.key != background }.keys.maxByOrNull { ratio(it, background) } ?: return null
        return Sample(text, background, ratio(text, background))
    }
}
