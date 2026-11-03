package app.guardianmode.ui.home

import app.guardianmode.cloud.ChangeRequest
import app.guardianmode.cloud.ChangeRequests
import app.guardianmode.core.ReminderSchedule
import app.guardianmode.core.TileKind
import app.guardianmode.data.LocalState
import app.guardianmode.data.PhotoItem
import app.guardianmode.protection.Features
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class TileUi(
    val id: String,
    val kind: TileKind,
    val label: String,
    val target: String = "",
    val phone: String = "",
    val photoUrl: String? = null,
)

data class HomeUi(
    val time: String,
    val date: String,
    val greeting: String,
    val photo: PhotoItem?,
    val showPhotoCard: Boolean,
    val nextReminder: Pair<String, String>?,
    val tiles: List<TileUi>,
    val columns: Int,
    val paused: Boolean,
    val paired: Boolean,
    val needsAttention: Boolean,
    /** One calm line under the greeting when pause cards can't arm yet (GM-PAUSE-009), else null. */
    val note: String? = null,
    val pendingHelper: app.guardianmode.data.HelperInfo? = null,
    /** A helper's risky change waiting for the parent's yes or no (docs/SECURITY.md §3). */
    val pendingRequest: RequestUi? = null,
)

/** "Sarah wants to turn off the pause before sending money." */
data class RequestUi(
    val id: String,
    val name: String,
    val photoUrl: String?,
    /** What the change does, in the parent's words, built from the change itself. */
    val what: String,
)

/** Pure mapping from stored state to what the home screen shows, so it is easy to test. */
object HomeModel {
    private val timeFormat = DateTimeFormatter.ofPattern("h:mm", Locale.getDefault())
    private val dateFormat = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())
    private val reminderTime = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

    fun build(
        state: LocalState,
        now: ZonedDateTime,
        appLabel: (String) -> String? = { null },
        needsAttention: Boolean = false,
        requests: List<ChangeRequest> = emptyList(),
        screeningRoleHeld: Boolean = true,
    ): HomeUi {
        val config = state.config
        val people = config.family.associateBy { it.id }
        val contacts = config.contacts.associateBy { it.id }
        val tiles = config.home.tiles.mapNotNull { tile ->
            when (tile.kind) {
                TileKind.CALL_CONTACT -> {
                    val fam = people[tile.target]
                    val con = contacts[tile.target]
                    val name = tile.label.ifBlank { fam?.name ?: con?.name ?: return@mapNotNull null }
                    TileUi(tile.id, tile.kind, name, tile.target, phone = fam?.phone ?: con?.phone ?: "", photoUrl = fam?.photoUrl ?: con?.photoUrl)
                }
                TileKind.APP -> {
                    val label = tile.label.ifBlank { appLabel(tile.target) ?: return@mapNotNull null }
                    TileUi(tile.id, tile.kind, label, tile.target)
                }
                else -> TileUi(tile.id, tile.kind, tile.label.ifBlank { defaultLabel(tile.kind) }, tile.target)
            }
        }
        // Reminders follow the zone the alarms use; the time shown is the phone's own clock.
        val reminderNow = now.withZoneSameInstant(ReminderSchedule.zoneOf(config.checkIn.timezone))
        val upcoming = ReminderSchedule.upcomingToday(config.reminders, reminderNow)
            .firstOrNull { it.at.isBefore(now.plusHours(2)) }
            ?.let { it.reminder.title to it.at.withZoneSameInstant(now.zone).format(reminderTime) }
        val photo = (state.photos + state.localPhotos).maxByOrNull { it.createdAt }
        return HomeUi(
            time = now.format(timeFormat),
            date = now.format(dateFormat),
            greeting = greeting(now, config.parentName),
            photo = photo,
            showPhotoCard = config.home.showPhotoCard,
            nextReminder = upcoming,
            tiles = tiles,
            columns = config.home.columns.coerceIn(1, 3),
            paused = state.isPaused(now.toInstant().toEpochMilli()),
            paired = state.pairing != null,
            needsAttention = needsAttention,
            note = if (pauseCardsWaitForScreening(state, screeningRoleHeld)) PAUSE_CARDS_WAIT_FOR_SCREENING else null,
            pendingHelper = state.helpers.firstOrNull { it.pending },
            pendingRequest = pendingRequest(state, requests, now.toInstant().toEpochMilli()),
        )
    }

    /** Shown with "Check me" when Guardian isn't the phone's call screener (GM-PAUSE-009, GM-SCRN-009). */
    const val PAUSE_CARDS_WAIT_FOR_SCREENING = "Pause cards stay off until Guardian screens your calls."

    /**
     * GM-PAUSE-009: an unscreened call is never read as unknown unless Guardian holds the
     * call-screening role, so pause cards the parent agreed to can't arm until it does.
     * True only while that's the case and pause cards are switched on.
     */
    fun pauseCardsWaitForScreening(state: LocalState, screeningRoleHeld: Boolean): Boolean =
        state.consent[Features.PAUSE] == true && state.config.protection.pauseTripwires.enabled && !screeningRoleHeld

    /**
     * The oldest request from someone who is an approved helper right now. The name shown
     * is the helper's own, from the family list, not the one written on the request.
     */
    fun pendingRequest(state: LocalState, requests: List<ChangeRequest>, now: Long): RequestUi? {
        val helpers = state.helpers.filter { !it.pending }.associateBy { it.uid }
        return requests.firstNotNullOfOrNull { r ->
            val helper = helpers[r.byUid] ?: return@firstNotNullOfOrNull null
            RequestUi(r.id, helper.name.ifBlank { r.byName.ifBlank { "A helper" } }, helper.photoUrl, ChangeRequests.describe(r.changes, now))
        }
    }

    fun photoTitle(photo: PhotoItem, now: ZonedDateTime): String {
        val sent = Instant.ofEpochMilli(photo.createdAt).atZone(now.zone).toLocalDate()
        val day = when (sent) {
            now.toLocalDate() -> "Today"
            now.toLocalDate().minusDays(1) -> "Yesterday"
            else -> sent.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
        }
        return if (photo.fromName.isBlank()) day else "$day from ${photo.fromName}"
    }

    private fun greeting(now: ZonedDateTime, name: String): String {
        val part = when (now.hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
        return if (name.isBlank()) part else "$part, $name"
    }

    fun defaultLabel(kind: TileKind) = when (kind) {
        TileKind.PHONE -> "Phone"
        TileKind.MESSAGES -> "Messages"
        TileKind.CAMERA -> "Camera"
        TileKind.PHOTOS -> "Family photos"
        TileKind.REMINDERS -> "Reminders"
        TileKind.HELP -> "Help"
        TileKind.ALL_APPS -> "All my apps"
        TileKind.CALL_CONTACT, TileKind.APP -> ""
    }
}
