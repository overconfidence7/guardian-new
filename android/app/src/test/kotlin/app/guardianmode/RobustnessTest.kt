package app.guardianmode

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.guardianmode.UiChecks.offScreenSideways
import app.guardianmode.core.LockLevel
import app.guardianmode.data.LocalState
import app.guardianmode.ui.cards.CardActions
import app.guardianmode.ui.cards.CardContent
import app.guardianmode.ui.home.HomeActions
import app.guardianmode.ui.home.HomeModel
import app.guardianmode.ui.home.HomeScreen
import app.guardianmode.ui.home.TileUi
import app.guardianmode.ui.theme.GuardianTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** qa/antigravity/cases/16-robustness.md. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class RobustnessTest {

    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    /** Writes [content] as the saved state file, then opens the app's LocalStore on it. */
    private fun openWith(content: String?, check: (LocalState, File) -> Unit) {
        val dir = tmp.newFolder()
        val file = UiChecks.stateFile(dir)
        if (content != null) {
            file.parentFile!!.mkdirs()
            file.writeText(content)
        }
        val (store, scope) = UiChecks.openStore(context, dir)
        try {
            check(store.current, file)
        } finally {
            UiChecks.close(scope)
        }
    }

    @Test fun gm_rob_003_corruptStateStartsFresh() {
        openWith("{\"config\": 5}") { state, file ->
            assertThat(state).isEqualTo(LocalState())
            assertThat(state.onboardingDone).isFalse()
            // The bad file was replaced by a readable one.
            assertThat(file.readText()).isNotEqualTo("{\"config\": 5}")
        }
    }

    @Test fun gm_rob_003_emptyStateFileStartsFresh() {
        openWith("") { state, _ -> assertThat(state).isEqualTo(LocalState()) }
    }

    @Test fun gm_rob_003_garbageStateFileStartsFresh() {
        openWith("\u0000\u0001not json at all") { state, _ -> assertThat(state).isEqualTo(LocalState()) }
    }

    @Test fun gm_rob_003_unknownFieldsAreIgnoredAndTheRestKept() {
        val saved = app.guardianmode.core.GuardianJson.json.encodeToString(
            LocalState.serializer(),
            Fixtures.state.copy(lockCodeHash = "a".repeat(64), lockCodeSalt = "salt", config = Fixtures.config.copy(lock = Fixtures.config.lock.copy(level = LockLevel.LOCKED))),
        )
        val future = saved.replaceFirst("{", "{\"schemaVersion\": 2, \"somethingNew\": {\"x\": [1, 2]},")
        openWith(future) { state, _ ->
            assertThat(state.onboardingDone).isTrue()
            assertThat(state.config.parentName).isEqualTo("Margaret")
            assertThat(state.config.lock.level).isEqualTo(LockLevel.LOCKED)
            assertThat(state.lockCodeHash).isEqualTo("a".repeat(64))
            assertThat(state.helpers.map { it.name }).containsExactly("Sarah", "David")
        }
    }

    @Test fun gm_rob_003_noFileYetMeansDefaults() {
        openWith(null) { state, _ -> assertThat(state).isEqualTo(LocalState()) }
    }

    @Test fun gm_rob_007_rightToLeftHomeStaysOnScreen() {
        val actions = object : HomeActions {
            override fun onTile(tile: TileUi) = Unit
            override fun onPhoto() = Unit
            override fun onShield() = Unit
            override fun onAllApps() = Unit
            override fun onReminders() = Unit
            override fun onHelperDecision(uid: String, approve: Boolean) = Unit
        }
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                GuardianTheme { HomeScreen(HomeModel.build(Fixtures.state, Fixtures.now), Fixtures.now, actions) }
            }
        }
        val width = rule.onRoot().fetchSemanticsNode().boundsInRoot.width
        assertThat(rule.offScreenSideways(width)).isEmpty()
        rule.onNodeWithText("Good morning, Margaret").assertExists()
    }

    @Test fun gm_rob_007_rightToLeftPauseCardStaysOnScreen() {
        val card = object : CardActions {
            override fun callFamily(phone: String) = Unit
            override fun continueAnyway() = Unit
            override fun goHome() = Unit
            override fun removeApp() = Unit
            override fun reminderDone() = Unit
            override fun snooze() = Unit
            override fun dismiss() = Unit
        }
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                GuardianTheme { CardContent(Fixtures.pauseDuringCall, Fixtures.config, card) }
            }
        }
        val width = rule.onRoot().fetchSemanticsNode().boundsInRoot.width
        assertThat(rule.offScreenSideways(width)).isEmpty()
        rule.onNodeWithText("Hold on a minute.").assertExists()
    }

    @Test fun gm_rob_003_lockStateSurvivesAReopen() = runBlocking<Unit> {
        val dir = tmp.newFolder()
        val (store, scope) = UiChecks.openStore(context, dir)
        store.update { it.copy(onboardingDone = true, lockFailures = 3, consent = mapOf("pause" to true)) }
        UiChecks.close(scope)
        val (again, scope2) = UiChecks.openStore(context, dir)
        try {
            assertThat(again.current.lockFailures).isEqualTo(3)
            assertThat(again.current.consent).containsExactly("pause", true)
        } finally {
            UiChecks.close(scope2)
        }
    }
}
