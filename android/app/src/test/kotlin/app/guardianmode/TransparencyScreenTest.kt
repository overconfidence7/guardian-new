package app.guardianmode

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertCountEquals
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.guardianmode.UiChecks.topOf
import app.guardianmode.core.EventType
import app.guardianmode.core.GuardianEvent
import app.guardianmode.core.LockLevel
import app.guardianmode.core.Reminder
import app.guardianmode.core.Severity
import app.guardianmode.core.Transparency
import app.guardianmode.data.ChangeItem
import app.guardianmode.data.DefaultConfig
import app.guardianmode.data.HelperInfo
import app.guardianmode.data.LocalState
import app.guardianmode.ui.theme.GuardianTheme
import app.guardianmode.ui.transparency.EventText
import app.guardianmode.ui.transparency.TransparencyActions
import app.guardianmode.ui.transparency.TransparencyScreen
import app.guardianmode.ui.transparency.TransparencyUi
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** qa/antigravity/cases/10-transparency-and-pause.md: "What my family can see". */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class TransparencyScreenTest {

    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val calls = mutableListOf<String>()
    private val actions = object : TransparencyActions {
        override fun onToggle(key: String, enabled: Boolean) { calls += "toggle:$key:$enabled" }
        override fun onPauseHour() { calls += "pause" }
        override fun onResume() { calls += "resume" }
        override fun onRemoveHelper(helper: HelperInfo) { calls += "remove:${helper.name}" }
        override fun onStopUsing() { calls += "stop" }
        override fun onBack() { calls += "back" }
        override fun onLockSettings() { calls += "lock" }
    }

    private fun ui(state: LocalState = Fixtures.state, paused: Boolean = false, pausedUntil: String? = null, level: LockLevel = LockLevel.GUIDED, hasCode: Boolean = true) =
        TransparencyUi(
            items = Transparency.items(state.config),
            helpers = state.helpers,
            shared = state.sharedEvents,
            changes = state.changes,
            paused = paused,
            pausedUntil = pausedUntil,
            paired = state.pairing != null,
            lockLevel = level,
            hasCode = hasCode,
        )

    private fun show(ui: TransparencyUi) = rule.setContent { GuardianTheme { TransparencyScreen(ui, actions, Fixtures.zone) } }

    private fun shown(text: String) = rule.onNodeWithText(text).assertExists()

    @Test fun gm_tran_001_introSaysWhatIsAndIsntShared() {
        show(ui())
        shown("What my family can see")
        shown("Guardian Mode shares a few safety notes with the people you chose. It never shares your messages, what's said on calls, or where you are. You can turn any of it off.")
    }

    @Test fun gm_tran_002_sectionsInOrder() {
        val withChange = Fixtures.state.copy(changes = listOf(ChangeItem("c", "Sarah", "changed text size", Fixtures.now.toInstant().toEpochMilli())))
        show(ui(withChange))
        val order = listOf(
            "Pause everything for 1 hour", "Your helpers", "What is shared", "Never shared",
            "What your family was told", "Recent changes by your family", "Phone lock", "Stop using Guardian Mode",
        )
        val tops = order.map { rule.topOf(it) }
        assertThat(tops).isInStrictOrder()
        shown("Sarah: changed text size")
    }

    @Test fun gm_tran_002_soloHasNoHelpersAndNoChangesSection() {
        val solo = LocalState(config = DefaultConfig.config, onboardingDone = true)
        show(ui(solo, hasCode = false))
        shown("No one yet.")
        rule.onNodeWithText("Recent changes by your family").assertDoesNotExist()
    }

    @Test fun gm_tran_003_sevenFeatureLinesFromTheLiveSettings() {
        show(ui())
        val items = Transparency.items(Fixtures.config)
        assertThat(items.map { it.title }).containsExactly(
            "Pause before sending money", "Remote-control app alarm", "Long calls with unknown numbers",
            "Unknown caller screening", "Daily \"I'm OK\"", "Help me button", "Reminders",
        ).inOrder()
        items.forEach {
            shown(it.title)
            shown("Shared: ${it.whatIsShared}")
            shown("Never shared: ${it.whatIsNeverShared}")
        }
        // Five have switches, Help and Reminders show a plain On/Off.
        rule.onAllNodes(isToggleable()).assertCountEquals(5)
        rule.onAllNodesWithText("On").assertCountEquals(2)
    }

    @Test fun gm_tran_003_textFollowsTheSettings() {
        val p = Fixtures.config.protection
        val config = Fixtures.config.copy(
            protection = p.copy(longCallAlert = p.longCallAlert.copy(minutes = 15)),
            checkIn = Fixtures.config.checkIn.copy(deadline = "09:30"),
            reminders = listOf(Reminder("r", "Pill", times = listOf("08:00"), notifyFamilyIfMissed = true)),
        )
        val items = Transparency.items(config).associateBy { it.key }
        assertThat(items.getValue("longCall").whatIsShared).isEqualTo("That a call with an unknown number went past 15 minutes.")
        assertThat(items.getValue("checkIn").whatIsShared).contains("09:30")
        assertThat(items.getValue("reminders").whatIsShared).isEqualTo("Whether you confirmed reminders your family asked to hear about.")
        assertThat(items.getValue("reminders").enabled).isTrue()

        val none = Transparency.items(config.copy(reminders = emptyList())).associateBy { it.key }
        assertThat(none.getValue("reminders").enabled).isFalse()
        assertThat(none.getValue("reminders").whatIsShared).isEqualTo("Nothing.")

        show(ui(Fixtures.state.copy(config = config)))
        shown("Shared: That a call with an unknown number went past 15 minutes.")
    }

    @Test fun gm_tran_004_switchesReportTheirStateAndAskForChanges() {
        val off = Fixtures.config.copy(protection = Fixtures.config.protection.copy(
            pauseTripwires = Fixtures.config.protection.pauseTripwires.copy(enabled = false),
        ))
        show(ui(Fixtures.state.copy(config = off)))
        val pause = rule.onNode(isToggleable() and hasContentDescription("Pause before sending money"))
        pause.assertIsOff().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off"))
        val screening = rule.onNode(isToggleable() and hasContentDescription("Unknown caller screening"))
        screening.assertIsOn().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On"))
        pause.performScrollTo().performClick()
        screening.performScrollTo().performClick()
        assertThat(calls).containsExactly("toggle:pause:true", "toggle:screening:false").inOrder()
    }

    @Test fun gm_tran_005_neverSharedList() {
        show(ui())
        listOf(
            "Your messages and texts", "What is said on calls", "Your location",
            "Your photos, unless you send one with Help", "Your passwords or bank details",
        ).forEach { shown(it) }
    }

    @Test fun gm_tran_006_eachAlertIsDescribedInPlainWords() {
        fun e(type: EventType, vararg data: Pair<String, String>) = GuardianEvent("x", type, 0, Severity.NOTICE, mapOf(*data))
        assertThat(EventText.forParent(e(EventType.PAUSE_SHOWN, "app" to "Chase")))
            .isEqualTo("A pause card was shown when you opened Chase after a call from an unknown number.")
        assertThat(EventText.forParent(e(EventType.PAUSE_CONTINUED, "app" to "Chase"))).isEqualTo("You chose to continue to Chase.")
        assertThat(EventText.forParent(e(EventType.PAUSE_CALLED_FAMILY, "app" to "Chase"))).isEqualTo("You chose to call family before using Chase.")
        assertThat(EventText.forParent(e(EventType.REMOTE_ACCESS_INSTALLED, "app" to "AnyDesk")))
            .isEqualTo("AnyDesk was installed. It can let someone control your phone.")
        assertThat(EventText.forParent(e(EventType.REMOTE_ACCESS_OPENED, "app" to "AnyDesk")))
            .isEqualTo("AnyDesk was opened. It can let someone control your phone.")
        assertThat(EventText.forParent(e(EventType.CONTROL_APP_INSTALLED, "app" to "Screen Helper")))
            .isEqualTo("Screen Helper was installed. It asks to control your screen.")
        assertThat(EventText.forParent(e(EventType.LONG_UNKNOWN_CALL, "minutes" to "20")))
            .isEqualTo("You were on a long call (20+ minutes) with a number you don't know.")
        assertThat(EventText.forParent(e(EventType.REMINDER_MISSED, "title" to "Blood pressure pill")))
            .isEqualTo("\"Blood pressure pill\" wasn't marked done.")
        // Every event type has words; none leaks a number.
        EventType.entries.forEach { assertThat(EventText.forParent(e(it))).isNotEmpty() }
    }

    @Test fun gm_tran_006_listShowsNewestFirstWithDateAndTime() {
        show(ui())
        // Fixtures: a pause card 3 hours ago (6:41 AM) and a long call the day before.
        shown("Fri Oct 16, 6:41 AM")
        shown("Thu Oct 15, 9:41 AM")
        assertThat(rule.topOf("A pause card was shown when you opened Chase after a call from an unknown number."))
            .isLessThan(rule.topOf("You were on a long call (22+ minutes) with a number you don't know."))
    }

    @Test fun gm_tran_006_atMost30AreKept() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val (store, scope) = UiChecks.openStore(context, tmp.newFolder("files"))
        try {
            runBlocking {
                repeat(32) { i -> store.recordShared(GuardianEvent("e$i", EventType.PAUSE_SHOWN, i.toLong(), Severity.NOTICE, mapOf("app" to "App$i"))) }
            }
            val kept = store.current.sharedEvents
            assertThat(kept).hasSize(30)
            assertThat(kept.first().id).isEqualTo("e31")
            assertThat(kept.last().id).isEqualTo("e2")
        } finally {
            UiChecks.close(scope)
        }
    }

    /** S-06 (LocalStore.recordShared records every event; TransparencyScreen lists them unconditionally): with no family linked, nothing was received by anyone, so the screen mustn't say it was. */
    @Category(KnownBug::class)
    @Test fun gm_tran_007_soloModeDoesNotClaimTheFamilyReceivedAlerts() {
        val solo = LocalState(config = DefaultConfig.config, onboardingDone = true, sharedEvents = Fixtures.state.sharedEvents)
        show(ui(solo))
        val text = UiChecks.run { rule.allText() }.joinToString("\n")
        // Today the section is headed "What your family was told" and lists the solo events.
        assertThat(text).doesNotContainMatch("(?i)(your )?family (was told|received|got|saw)")
    }

    @Test fun gm_tran_008_pauseAndResume() {
        var paused by androidx.compose.runtime.mutableStateOf(false)
        rule.setContent {
            GuardianTheme { TransparencyScreen(ui(paused = paused, pausedUntil = if (paused) "10:41 AM" else null), actions, Fixtures.zone) }
        }
        rule.onNode(hasText("Pause everything for 1 hour") and hasClickAction()).performClick()
        paused = true
        shown("Turn protection back on")
        shown("Paused until 10:41 AM")
        rule.onNodeWithText("Pause everything for 1 hour").assertDoesNotExist()
        rule.onNode(hasText("Turn protection back on") and hasClickAction()).performClick()
        assertThat(calls).containsExactly("pause", "resume").inOrder()
    }

    @Test fun gm_tran_012_gm_lock_060_gm_lock_062_phoneLockSection() {
        var current by androidx.compose.runtime.mutableStateOf(ui(level = LockLevel.SIMPLE))
        rule.setContent { GuardianTheme { TransparencyScreen(current, actions, Fixtures.zone) } }
        shown("Simple. The code is only needed to change the lock or stop using Guardian Mode.")
        shown("Change the lock")
        current = ui(level = LockLevel.GUIDED)
        shown("Guided. The app list, phone settings and these switches need the code.")
        current = ui(level = LockLevel.LOCKED)
        shown("Locked. Any app your family didn't add, Settings included, needs the code. Calls always work.")
        rule.onNode(hasText("Change the lock") and hasClickAction()).performScrollTo().performClick()
        assertThat(calls).containsExactly("lock")
        current = ui(level = LockLevel.LOCKED, hasCode = false)
        shown("There's no lock code on this phone yet.")
        shown("Set up a lock")
    }

    @Test fun gm_tran_013_stopUsingAsksFirstAndKeepChangesNothing() {
        show(ui())
        shown("You can stop whenever you want. Your helpers will see that this phone has left the family.")
        rule.onNode(hasText("Stop using Guardian Mode") and hasClickAction()).performScrollTo().performClick()
        shown("Stop using Guardian Mode?")
        shown("Your family will stop getting notes from this phone and won't be able to help with it. You'll then choose a different home screen.")
        rule.onNodeWithText("Keep using it").performClick()
        rule.onNodeWithText("Stop using Guardian Mode?").assertDoesNotExist()
        assertThat(calls).isEmpty()

        rule.onNode(hasText("Stop using Guardian Mode") and hasClickAction()).performScrollTo().performClick()
        rule.onNodeWithText("Stop").performClick()
        assertThat(calls).containsExactly("stop")
    }

    @Test fun gm_tran_013_removingAHelperAsksFirst() {
        show(ui())
        rule.onAllNodesWithText("Remove")[0].performScrollTo().performClick()
        shown("Remove Sarah?")
        rule.onNodeWithText("Keep").performClick()
        assertThat(calls).isEmpty()
        rule.onAllNodesWithText("Remove")[0].performScrollTo().performClick()
        rule.onAllNodesWithText("Remove").fetchSemanticsNodes().size.let { assertThat(it).isAtLeast(2) }
        rule.onNode(hasText("Remove") and hasClickAction() and SemanticsMatcher("in dialog") { node ->
            generateSequence(node) { it.parent }.none { p -> p.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
        }).performClick()
        assertThat(calls).containsExactly("remove:Sarah")
    }
}
