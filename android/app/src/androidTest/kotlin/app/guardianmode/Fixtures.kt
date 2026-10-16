package app.guardianmode

import app.guardianmode.core.AppCategory
import app.guardianmode.core.Contact
import app.guardianmode.core.DeviceConfig
import app.guardianmode.core.EventType
import app.guardianmode.core.FamilyContact
import app.guardianmode.core.GuardianEvent
import app.guardianmode.core.HomeLayout
import app.guardianmode.core.HomeTile
import app.guardianmode.core.ProtectionSettings
import app.guardianmode.core.Reminder
import app.guardianmode.core.SafeWordSettings
import app.guardianmode.core.Severity
import app.guardianmode.core.TileKind
import app.guardianmode.data.HelperInfo
import app.guardianmode.data.LocalState
import app.guardianmode.data.Pairing
import app.guardianmode.ui.cards.CardKind
import app.guardianmode.ui.cards.CardRequest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Copy of the unit-test fixtures for instrumented tests (separate source sets). */
object Fixtures {
    val zone: ZoneId = ZoneId.of("America/Chicago")
    val now: ZonedDateTime = ZonedDateTime.of(LocalDateTime.of(2026, 10, 16, 9, 41), zone)

    val config = DeviceConfig(
        parentName = "Margaret",
        family = listOf(
            FamilyContact("sarah", "Sarah", "Daughter", "+1 555 010 1111", isPrimaryHelper = true),
            FamilyContact("david", "David", "Son", "+1 555 010 2222"),
        ),
        contacts = listOf(Contact("patel", "Dr. Patel", "+1 555 010 3333")),
        home = HomeLayout(
            tiles = listOf(
                HomeTile("t1", TileKind.CALL_CONTACT, "sarah"),
                HomeTile("t2", TileKind.CALL_CONTACT, "david"),
                HomeTile("t3", TileKind.PHONE),
                HomeTile("t4", TileKind.MESSAGES),
                HomeTile("t5", TileKind.CAMERA),
                HomeTile("t6", TileKind.PHOTOS),
                HomeTile("t7", TileKind.REMINDERS),
                HomeTile("t8", TileKind.HELP),
            ),
        ),
        protection = ProtectionSettings(safeWord = SafeWordSettings(enabled = true, word = "bluebird")),
        reminders = listOf(Reminder("r1", "Blood pressure pill", "One white tablet with water", times = listOf("10:30", "20:00"))),
    )

    val state = LocalState(
        config = config,
        pairing = Pairing("fam", "dev", "Sarah"),
        onboardingDone = true,
        helpers = listOf(HelperInfo("u1", "Sarah", role = "owner"), HelperInfo("u2", "David")),
        sharedEvents = listOf(
            GuardianEvent("e1", EventType.PAUSE_SHOWN, now.minusHours(3).toInstant().toEpochMilli(), Severity.NOTICE, mapOf("app" to "Chase")),
            GuardianEvent("e2", EventType.LONG_UNKNOWN_CALL, now.minusDays(1).toInstant().toEpochMilli(), Severity.NOTICE, mapOf("minutes" to "22")),
        ),
    )

    val pause = CardRequest(CardKind.PAUSE, "com.chase.sig.android", "Chase", AppCategory.BANK, duringCall = false, minutesSinceCall = 4)
    val pauseDuringCall = pause.copy(duringCall = true, category = AppCategory.GIFT_CARD, appLabel = "Target")
    val remote = CardRequest(CardKind.REMOTE_ACCESS, "com.anydesk.anydeskandroid", "AnyDesk", justInstalled = true)
    val safeWord = CardRequest(CardKind.SAFE_WORD)
    val reminder = CardRequest(
        CardKind.REMINDER, reminderId = "r1", reminderTitle = "Blood pressure pill",
        reminderDetail = "One white tablet with water", reminderAt = now.toInstant().toEpochMilli(),
    )
}
