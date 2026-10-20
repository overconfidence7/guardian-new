package app.guardianmode

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.guardianmode.core.Reminder
import app.guardianmode.core.ReminderSchedule
import app.guardianmode.data.LocalState
import app.guardianmode.data.PhotoImporter
import app.guardianmode.data.PhotoItem
import app.guardianmode.ui.help.HelpActivity
import app.guardianmode.ui.home.HomeModel
import app.guardianmode.ui.home.PhotosScreen
import app.guardianmode.ui.home.RemindersScreen
import app.guardianmode.ui.theme.GuardianTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Help (12-help-and-reminders.md), photos (05-photos.md) and the reminders list. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class HelpPhotosRemindersTest {

    @get:Rule val rule = createEmptyComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val app get() = ApplicationProvider.getApplicationContext<GuardianApp>()

    private fun withState(state: LocalState) = runBlocking { app.container.store.update { state } }

    private fun openHelp(state: LocalState): ActivityScenario<HelpActivity> {
        withState(state)
        return ActivityScenario.launch(HelpActivity::class.java).also { rule.waitForIdle() }
    }

    /** S-07: a solo parent who taps Help must find something on the page that helps. */
    @Test fun gm_help_001_soloHelpIsNotADeadEnd() {
        openHelp(UiChecks.soloState()).use {
            rule.onNodeWithText("Ask your family for help").assertExists()
            val buttons = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().map { UiChecks.label(it) }
            assertThat(buttons.filterNot { it == "Back" || it == "Go back" }).isNotEmpty()
        }
    }

    @Test fun gm_help_002_familyButNotLinkedOffersACall() {
        openHelp(UiChecks.familyState()).use { scenario ->
            rule.onNodeWithText("Ask Sarah for help").assertExists()
            rule.onNodeWithText("You can call Sarah for help.").assertExists()
            rule.onNodeWithText("Take a picture of my screen").assertDoesNotExist()
            rule.onNode(hasText("Call Sarah") and hasClickAction()).performClick()
            scenario.onActivity { activity ->
                val started: Intent = shadowOf(activity).nextStartedActivity
                assertThat(started.action).isAnyOf(Intent.ACTION_CALL, Intent.ACTION_DIAL)
                assertThat(started.data).isEqualTo(Uri.fromParts("tel", "+1 555 010 1111", null))
            }
        }
    }

    @Test fun gm_help_003_linkedPhoneOffersTheScreenshot() {
        openHelp(Fixtures.state).use {
            rule.onNodeWithText("Ask Sarah for help").assertExists()
            rule.onNodeWithText("We'll take a picture of your screen so Sarah can see what's confusing. You'll see the picture before it's sent.").assertExists()
            rule.onNodeWithText("Take a picture of my screen").assertExists()
            rule.onNodeWithText("Call Sarah").assertExists()
        }
    }
}

/** Composable-only parts of 05-photos.md and the reminders list in 12-help-and-reminders.md. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class PhotosAndRemindersScreenTest {

    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val now = Fixtures.now

    @Test fun gm_photo_001_emptyFamilyPhotosPage() {
        var added = 0
        rule.setContent { GuardianTheme { PhotosScreen(emptyList(), now, onBack = {}, onAdd = { added++ }) } }
        rule.onNodeWithText("Family photos").assertExists()
        rule.onNodeWithText("When your family sends photos, you'll find them here.").assertExists()
        rule.onNodeWithText("You can also tap Share in Google Photos, Amazon Photos or your gallery and pick \"Add to photo card\".").assertExists()
        rule.onNode(hasText("Add photos from this phone") and hasClickAction()).performClick()
        assertThat(added).isEqualTo(1)
    }

    @Test fun gm_photo_008_onlyLocalPhotosCanBeRemoved() {
        val local = PhotoItem("${PhotoImporter.LOCAL_PREFIX}1", "file:///x.jpg", "", createdAt = now.toInstant().toEpochMilli())
        val family = PhotoItem("fam1", "file:///nonexistent/p.jpg", "Sarah", createdAt = now.minusHours(1).toInstant().toEpochMilli())
        val removed = mutableListOf<String>()
        rule.setContent { GuardianTheme { PhotosScreen(listOf(family, local), now, onBack = {}, onAdd = {}, onRemove = { removed += it.id }) } }
        rule.onNodeWithText("Remove this photo").performClick()
        assertThat(removed).containsExactly(local.id)
    }

    @Test fun gm_photo_011_photoCardWording() {
        val today = PhotoItem("p", "u", "", createdAt = now.minusHours(1).toInstant().toEpochMilli())
        assertThat(HomeModel.photoTitle(today, now)).isEqualTo("Today")
        assertThat(HomeModel.photoTitle(today.copy(fromName = "Sarah"), now)).isEqualTo("Today from Sarah")
        assertThat(HomeModel.photoTitle(today.copy(createdAt = now.minusDays(1).toInstant().toEpochMilli()), now)).isEqualTo("Yesterday")
        assertThat(HomeModel.photoTitle(today.copy(createdAt = now.minusDays(3).toInstant().toEpochMilli()), now)).isEqualTo("Tuesday")
    }

    @Test fun gm_help_026_todaysRemindersInTimeOrderWithDoneMarked() {
        val reminders = listOf(
            Reminder("b", "Evening pill", times = listOf("20:00")),
            Reminder("a", "Morning pill", times = listOf("08:00", "13:00")),
            Reminder("w", "Weekday walk", times = listOf("10:00"), daysOfWeek = setOf(1, 2, 3, 4, 5)),
            Reminder("s", "Sunday call", times = listOf("11:00"), daysOfWeek = setOf(7)),
            Reminder("d", "Dentist", times = listOf("15:00"), date = "2026-10-16"),
            Reminder("o", "Other day", times = listOf("16:00"), date = "2026-10-17"),
        )
        val start = now.toLocalDate().atStartOfDay(now.zone)
        val today = ReminderSchedule.upcomingToday(reminders, start)
        assertThat(today.map { it.reminder.title })
            .containsExactly("Morning pill", "Weekday walk", "Morning pill", "Dentist", "Evening pill").inOrder()
        val doneKey = "a@${today.first().at.toInstant().toEpochMilli()}"
        rule.setContent { GuardianTheme { RemindersScreen(today, setOf(doneKey), onBack = {}) } }
        rule.onNodeWithText("Today's reminders").assertExists()
        rule.onAllNodesWithContentDescription("Done").fetchSemanticsNodes().let { assertThat(it).hasSize(1) }
        rule.onNodeWithText("Sunday call").assertDoesNotExist()
    }

    @Test fun gm_help_026_nothingElseToday() {
        rule.setContent { GuardianTheme { RemindersScreen(emptyList(), emptySet(), onBack = {}) } }
        rule.onNodeWithText("Nothing else today.").assertExists()
    }
}
