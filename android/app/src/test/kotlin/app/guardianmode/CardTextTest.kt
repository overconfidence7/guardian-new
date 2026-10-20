package app.guardianmode

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.guardianmode.UiChecks.allText
import app.guardianmode.core.AppCategory
import app.guardianmode.core.DeviceConfig
import app.guardianmode.core.FamilyContact
import app.guardianmode.core.SafeWordSettings
import app.guardianmode.data.DefaultConfig
import app.guardianmode.ui.cards.CardActions
import app.guardianmode.ui.cards.CardContent
import app.guardianmode.ui.cards.CardKind
import app.guardianmode.ui.cards.CardRequest
import app.guardianmode.ui.theme.GuardianTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The exact words and buttons of every full-screen card (ui/cards/CardContent.kt), for
 * qa/antigravity/cases/06-pause-cards.md, 07-remote-control-alarm.md, 09-long-calls-and-safe-word.md
 * and the reminder card in 12-help-and-reminders.md.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class CardTextTest {

    @get:Rule val rule = createComposeRule()

    private val done = mutableListOf<String>()
    private val actions = object : CardActions {
        override fun callFamily(phone: String) { done += "call:$phone" }
        override fun continueAnyway() { done += "continue" }
        override fun goHome() { done += "home" }
        override fun removeApp() { done += "remove" }
        override fun reminderDone() { done += "done" }
        override fun snooze() { done += "snooze" }
        override fun dismiss() { done += "dismiss" }
    }

    private fun show(request: CardRequest, config: DeviceConfig = Fixtures.config) =
        rule.setContent { GuardianTheme { CardContent(request, config, actions) } }

    private fun shown(text: String) = rule.onNodeWithText(text).assertExists()

    private fun tap(text: String) = rule.onNode(hasText(text) and hasClickAction()).performScrollTo().performClick()

    private fun pause(category: AppCategory, label: String, duringCall: Boolean) =
        CardRequest(CardKind.PAUSE, "pkg", label, category, duringCall = duringCall)

    @Test fun gm_pause_001_cardAfterTheCallHasNoHangUpLine() {
        show(pause(AppCategory.BANK, "Chase", duringCall = false))
        shown("Hold on a minute.")
        shown("Did someone on the phone ask you to open Chase?")
        shown("Real banks, police, Medicare and tech companies never ask you to move money from your bank.")
        assertThat(rule.allText().joinToString(" ")).doesNotContain("It's OK to hang up.")
        // The headline is announced first.
        rule.onNodeWithText("Hold on a minute.").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test fun gm_pause_002_cardDuringTheCallSaysItIsOkToHangUp() {
        show(pause(AppCategory.BANK, "Chase", duringCall = true))
        shown("It's OK to hang up. Real banks, police, Medicare and tech companies never ask you to move money from your bank.")
    }

    @Test fun gm_pause_003_everyCategoryHasItsOwnWords() {
        val words = mapOf(
            AppCategory.BANK to "move money from your bank",
            AppCategory.PAYMENT to "send money",
            AppCategory.GIFT_CARD to "buy gift cards",
            AppCategory.CRYPTO to "send crypto or Bitcoin",
        )
        var request by androidx.compose.runtime.mutableStateOf(pause(AppCategory.BANK, "Chase", false))
        rule.setContent { GuardianTheme { CardContent(request, Fixtures.config, actions) } }
        listOf(
            Triple(AppCategory.PAYMENT, "Venmo", "send money"),
            Triple(AppCategory.PAYMENT, "Zelle", "send money"),
            Triple(AppCategory.GIFT_CARD, "Target", "buy gift cards"),
            Triple(AppCategory.CRYPTO, "Coinbase", "send crypto or Bitcoin"),
            Triple(AppCategory.BANK, "Chase", "move money from your bank"),
        ).forEach { (category, app, phrase) ->
            assertThat(words[category]).isEqualTo(phrase)
            request = pause(category, app, false)
            rule.waitForIdle()
            shown("Did someone on the phone ask you to open $app?")
            shown("Real banks, police, Medicare and tech companies never ask you to $phrase.")
        }
    }

    @Test fun gm_pause_020_gm_pause_021_gm_pause_022_buttonsDoWhatTheySay() {
        show(pause(AppCategory.BANK, "Chase", false))
        tap("Call Sarah first")
        tap("I'm fine, continue")
        tap("Go to my home screen")
        assertThat(done).containsExactly("call:+1 555 010 1111", "continue", "home").inOrder()
    }

    @Test fun gm_pause_021_callButtonOnlyWithAFamilyNumber() {
        val noNumbers = Fixtures.config.copy(family = Fixtures.config.family.map { it.copy(phone = "") })
        show(pause(AppCategory.BANK, "Chase", false), noNumbers)
        assertThat(rule.allText().joinToString(" ")).doesNotContain("Call ")
        shown("I'm fine, continue")
        shown("Go to my home screen")
    }

    @Test fun gm_pause_021_solo_noCallButton() {
        show(pause(AppCategory.CRYPTO, "Coinbase", true), DefaultConfig.config)
        assertThat(rule.allText().joinToString(" ")).doesNotContain("Call ")
        tap("I'm fine, continue")
        assertThat(done).containsExactly("continue")
    }

    @Test fun gm_pause_021_callsThePrimaryHelperWithANumber() {
        // A primary helper without a number is skipped for one who has a number.
        val config = Fixtures.config.copy(
            family = listOf(
                FamilyContact("sarah", "Sarah", "Daughter", "", isPrimaryHelper = true),
                FamilyContact("david", "David", "Son", "+1 555 010 2222"),
            ),
        )
        show(pause(AppCategory.BANK, "Chase", false), config)
        tap("Call David first")
        assertThat(done).containsExactly("call:+1 555 010 2222")
    }

    @Test fun gm_pause_041_notificationWhenTheCardCannotCoverTheScreen() {
        val request = pause(AppCategory.BANK, "Chase", false)
        assertThat(request.notificationTitle).isEqualTo("Hold on a minute")
        assertThat(request.notificationText).isEqualTo("Did someone on the phone ask you to open Chase? Tap to talk it over with family first.")
    }

    @Test fun gm_racc_001_remoteControlWarningWording() {
        show(Fixtures.remote)
        shown("AnyDesk can let someone control your phone.")
        shown("Real companies like Microsoft, Apple, Amazon or your bank never ask you to install an app like this over the phone.")
        shown("If someone on the phone asked you to install it, it's OK to hang up.")
        shown("Call Sarah")
        shown("Remove AnyDesk")
        shown("I trust this app")
    }

    @Test fun gm_racc_012_milderWarningForAppsThatCanControlTheScreen() {
        show(CardRequest(CardKind.REMOTE_ACCESS, "com.example.helper", "Screen Helper", justInstalled = true, knownRemoteAccessApp = false))
        shown("Screen Helper asks to control your screen.")
        assertThat(CardRequest(CardKind.REMOTE_ACCESS, appLabel = "Screen Helper", knownRemoteAccessApp = false).notificationTitle)
            .isEqualTo("A new app can control your screen")
    }

    @Test fun gm_racc_006_gm_racc_007_gm_racc_008_warningButtons() {
        show(Fixtures.remote)
        tap("Call Sarah")
        tap("Remove AnyDesk")
        tap("I trust this app")
        // "I trust this app" only closes the warning; it adds nothing to a trusted list.
        assertThat(done).containsExactly("call:+1 555 010 1111", "remove", "dismiss").inOrder()
    }

    @Test fun gm_racc_001_soloWarningHasNoCallButton() {
        show(Fixtures.remote, DefaultConfig.config)
        assertThat(rule.allText().joinToString(" ")).doesNotContain("Call ")
        shown("Remove AnyDesk")
    }

    @Test fun gm_call_010_safeWordHiddenUntilAsked() {
        show(Fixtures.safeWord)
        shown("Did the caller say they're family?")
        shown("If anyone said they're a grandchild or relative in trouble, ask for your family safe word before doing anything. Your real family will know it.")
        assertThat(rule.allText().joinToString(" ")).doesNotContain("bluebird")
        tap("Show our safe word")
        shown("Your safe word is \"bluebird\"")
        rule.onNodeWithText("Show our safe word").assertDoesNotExist()
        tap("Call Sarah")
        tap("Call David")
        tap("Everything's fine")
        assertThat(done).containsExactly("call:+1 555 010 1111", "call:+1 555 010 2222", "dismiss").inOrder()
    }

    @Test fun gm_call_010_callButtonsForAtMostTwoFamilyMembers() {
        val config = Fixtures.config.copy(family = Fixtures.config.family + FamilyContact("amy", "Amy", "Niece", "+1 555 010 5555"))
        show(Fixtures.safeWord, config)
        shown("Call Sarah")
        shown("Call David")
        rule.onNodeWithText("Call Amy").assertDoesNotExist()
    }

    @Test fun gm_call_013_noSafeWordSetMeansNoRevealButton() {
        val solo = DefaultConfig.config
        assertThat(solo.protection.safeWord.word).isEmpty()
        show(Fixtures.safeWord, solo)
        shown("Did the caller say they're family?")
        rule.onNodeWithText("Show our safe word").assertDoesNotExist()
        val buttons = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().map { UiChecks.label(it) }
        assertThat(buttons).containsExactly("Everything's fine")
    }

    @Test fun gm_call_013_wordSetButEmptyFamily() {
        show(Fixtures.safeWord, DefaultConfig.config.copy(protection = DefaultConfig.config.protection.copy(safeWord = SafeWordSettings(enabled = true, word = "bluebird"))))
        tap("Show our safe word")
        shown("Your safe word is \"bluebird\"")
    }

    @Test fun gm_help_020_reminderCardShowsTimeTitleDetailAndButtons() {
        val at = LocalDateTime.of(2026, 10, 16, 8, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        show(Fixtures.reminder.copy(reminderAt = at))
        shown("8:00 AM")
        shown("Blood pressure pill")
        shown("One white tablet with water")
        tap("Done")
        tap("Remind me in 10 minutes")
        assertThat(done).containsExactly("done", "snooze").inOrder()
    }
}
