package app.guardianmode

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.guardianmode.UiChecks.offScreenSideways
import app.guardianmode.core.Contact
import app.guardianmode.core.DisplaySettings
import app.guardianmode.core.HomeTile
import app.guardianmode.core.Reminder
import app.guardianmode.core.TileKind
import app.guardianmode.data.LocalState
import app.guardianmode.ui.home.AllAppsScreen
import app.guardianmode.ui.home.AppEntry
import app.guardianmode.ui.home.HomeActions
import app.guardianmode.ui.home.HomeModel
import app.guardianmode.ui.home.HomeScreen
import app.guardianmode.ui.home.HomeUi
import app.guardianmode.ui.home.TileUi
import app.guardianmode.ui.theme.GuardianTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZonedDateTime

/** qa/antigravity/cases/04-home-screen.md, through HomeModel and the home screen composable. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class HomeScreenTest {

    @get:Rule val rule = createComposeRule()

    private val now = Fixtures.now // Friday, October 16 2026, 9:41 AM in Chicago

    private val taps = mutableListOf<String>()
    private val actions = object : HomeActions {
        override fun onTile(tile: TileUi) { taps += "tile:${tile.label}" }
        override fun onPhoto() { taps += "photo" }
        override fun onShield() { taps += "shield" }
        override fun onAllApps() { taps += "apps" }
        override fun onReminders() { taps += "reminders" }
        override fun onHelperDecision(uid: String, approve: Boolean) { taps += "helper:$uid:$approve" }
    }

    private fun show(ui: HomeUi, display: DisplaySettings = DisplaySettings(), at: ZonedDateTime = now) =
        rule.setContent { GuardianTheme(display) { HomeScreen(ui, at, actions) } }

    @Test fun gm_home_001_defaultSoloHomeScreen() {
        val ui = HomeModel.build(UiChecks.soloState(), now)
        assertThat(ui.time).isEqualTo("9:41")
        assertThat(ui.date).isEqualTo("Friday, October 16")
        assertThat(ui.greeting).isEqualTo("Good morning")
        assertThat(ui.tiles.map { it.label }).containsExactly("Phone", "Messages", "Camera", "Photos", "Reminders", "Help").inOrder()
        assertThat(ui.photo).isNull()
        show(ui)
        rule.onNodeWithText("9:41").assertExists()
        rule.onNodeWithText("Friday, October 16").assertExists()
        rule.onNodeWithText("Good morning").assertExists()
        rule.onNodeWithText("Welcome to your new home screen.").assertExists()
        rule.onNodeWithContentDescription("Protected. What my family can see.").assertExists()
        val grid = rule.onNode(androidx.compose.ui.test.hasScrollToIndexAction())
        ui.tiles.forEach {
            grid.performScrollToNode(androidx.compose.ui.test.hasContentDescription(it.label))
            rule.onNodeWithContentDescription(it.label).assertExists()
        }
        grid.performScrollToNode(hasText("All my apps"))
        rule.onNodeWithText("All my apps").assertExists()
    }

    @Test fun gm_home_001_greetingFollowsTheHour() {
        val state = UiChecks.soloState()
        fun at(hour: Int) = HomeModel.build(state, now.withHour(hour)).greeting
        assertThat(at(4)).isEqualTo("Good evening")
        assertThat(at(5)).isEqualTo("Good morning")
        assertThat(at(11)).isEqualTo("Good morning")
        assertThat(at(12)).isEqualTo("Good afternoon")
        assertThat(at(16)).isEqualTo("Good afternoon")
        assertThat(at(17)).isEqualTo("Good evening")
        // With a name once the family sets one.
        assertThat(HomeModel.build(Fixtures.state, now.withHour(14)).greeting).isEqualTo("Good afternoon, Margaret")
    }

    @Test fun gm_home_004_tilesFollowTheFamilysConfigAndDropBrokenOnes() {
        val config = Fixtures.config.copy(
            contacts = Fixtures.config.contacts,
            home = Fixtures.config.home.copy(
                tiles = listOf(
                    HomeTile("t1", TileKind.CALL_CONTACT, "sarah"),
                    HomeTile("t2", TileKind.CALL_CONTACT, "david"),
                    HomeTile("t3", TileKind.CALL_CONTACT, "patel"),
                    HomeTile("t4", TileKind.PHONE),
                    HomeTile("t5", TileKind.MESSAGES),
                    HomeTile("t6", TileKind.CAMERA),
                    HomeTile("t7", TileKind.PHOTOS),
                    HomeTile("t8", TileKind.REMINDERS),
                    HomeTile("t9", TileKind.APP, "com.example.notes"),
                    HomeTile("t10", TileKind.HELP),
                    HomeTile("x1", TileKind.CALL_CONTACT, "nobody"),
                    HomeTile("x2", TileKind.APP, "com.not.installed"),
                ),
            ),
        )
        val labels = mapOf("com.example.notes" to "Notes")
        val ui = HomeModel.build(Fixtures.state.copy(config = config), now, appLabel = { labels[it] })
        assertThat(ui.tiles.map { it.label })
            .containsExactly("Sarah", "David", "Dr. Patel", "Phone", "Messages", "Camera", "Family photos", "Reminders", "Notes", "Help")
            .inOrder()
        assertThat(ui.tiles.first { it.label == "Dr. Patel" }.phone).isEqualTo("+1 555 010 3333")

        show(ui)
        // Person tiles read as "Call <name>" and show initials without a photo.
        rule.onNodeWithContentDescription("Call Sarah").assertExists().performClick()
        assertThat(taps).containsExactly("tile:Sarah")
    }

    @Test fun gm_home_005_columnsAreClampedBetweenOneAndThree() {
        fun cols(n: Int) = HomeModel.build(Fixtures.state.copy(config = Fixtures.config.copy(home = Fixtures.config.home.copy(columns = n))), now).columns
        assertThat(cols(1)).isEqualTo(1)
        assertThat(cols(3)).isEqualTo(3)
        assertThat(cols(5)).isEqualTo(3)
        assertThat(cols(0)).isEqualTo(1)
    }

    @Test fun gm_home_006_shieldChipStatesAndOneTapToTransparency() {
        val base = HomeModel.build(Fixtures.state, now)
        var ui by androidx.compose.runtime.mutableStateOf(base)
        rule.setContent { GuardianTheme { HomeScreen(ui, now, actions) } }
        rule.onNodeWithContentDescription("Protected. What my family can see.").assertExists()
        rule.onNodeWithText("Protected").assertExists()

        ui = base.copy(needsAttention = true)
        rule.onNodeWithContentDescription("Check me. What my family can see.").assertExists()
        rule.onNodeWithText("Check me").assertExists()

        ui = base.copy(needsAttention = true, paused = true)
        rule.onNodeWithContentDescription("Paused. What my family can see.").assertExists().performClick()
        rule.onNodeWithText("Paused").assertExists()
        assertThat(taps).containsExactly("shield")

        // Paused comes from the stored pause time.
        val paused = Fixtures.state.copy(pausedUntil = now.plusMinutes(5).toInstant().toEpochMilli())
        assertThat(HomeModel.build(paused, now).paused).isTrue()
        assertThat(HomeModel.build(paused, now.plusMinutes(6)).paused).isFalse()
    }

    @Test fun gm_home_007_nextReminderStripOnlyWithinTwoHours() {
        val in90 = now.plusMinutes(90).toLocalTime()
        val in180 = now.plusMinutes(180).toLocalTime()
        fun stateWith(time: java.time.LocalTime) = Fixtures.state.copy(
            config = Fixtures.config.copy(
                reminders = listOf(Reminder("r", "Blood pressure pill", times = listOf("%02d:%02d".format(time.hour, time.minute)))),
            ),
        )
        val soon = HomeModel.build(stateWith(in90), now)
        assertThat(soon.nextReminder).isEqualTo("Blood pressure pill" to "11:11 AM")
        assertThat(HomeModel.build(stateWith(in180), now).nextReminder).isNull()

        show(soon)
        rule.onNodeWithText("Coming up at 11:11 AM").assertExists()
        rule.onNode(hasText("Blood pressure pill") and hasClickAction()).performClick()
        assertThat(taps).containsExactly("reminders")
    }

    @Test fun gm_home_010_allMyAppsEmptyAndWithApps() {
        var apps by androidx.compose.runtime.mutableStateOf(emptyList<AppEntry>())
        val opened = mutableListOf<String>()
        var settings = 0
        rule.setContent { GuardianTheme { AllAppsScreen(apps, { opened += it.label }, { settings++ }, {}) } }
        rule.onNodeWithText("All my apps").assertExists()
        rule.onNodeWithText("Your family hasn't added any apps yet.").assertExists()
        rule.onNodeWithText("Phone settings").performClick()
        assertThat(settings).isEqualTo(1)

        apps = listOf(AppEntry("com.example.notes", "Notes"))
        rule.onNodeWithText("Your family hasn't added any apps yet.").assertDoesNotExist()
        rule.onNodeWithText("Notes").performClick()
        assertThat(opened).containsExactly("Notes")
        rule.onNodeWithText("Phone settings").assertExists()
    }

    @Test fun gm_home_014_longContentStaysOnScreenAtLargestText() {
        val name = "Margaret Elizabeth Henderson-Worthington"
        val config = Fixtures.config.copy(
            parentName = name,
            contacts = listOf(Contact("long", "Doctor Bartholomew Fitzgerald", "+1 555 010 9999")),
            home = Fixtures.config.home.copy(
                tiles = listOf(HomeTile("l", TileKind.CALL_CONTACT, "long"), HomeTile("h", TileKind.HELP), HomeTile("p", TileKind.PHONE)),
            ),
            reminders = listOf(
                Reminder("r", "Take the large white blood pressure tablet with a full glass of water before breakfast", times = listOf("10:30")),
            ),
        )
        val state = LocalState(config = config, onboardingDone = true)
        val ui = HomeModel.build(state, now)
        assertThat(ui.greeting).isEqualTo("Good morning, $name")
        show(ui, DisplaySettings(fontScale = 2.0f))
        rule.waitForIdle()
        val width = rule.onRoot().fetchSemanticsNode().boundsInRoot.width
        assertThat(rule.offScreenSideways(width)).isEmpty()
        rule.onNodeWithText("Good morning, $name").assertExists()
    }
}
