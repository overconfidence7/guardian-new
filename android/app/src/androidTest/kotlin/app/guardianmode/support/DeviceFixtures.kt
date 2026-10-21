package app.guardianmode.support

import app.guardianmode.Fixtures
import app.guardianmode.core.AllowedNumber
import app.guardianmode.core.HomeTile
import app.guardianmode.core.Reminder
import app.guardianmode.core.TileKind
import app.guardianmode.data.DefaultConfig
import app.guardianmode.data.LocalState
import app.guardianmode.protection.Features
import java.util.TimeZone

/**
 * The two starting states from the runbook: solo (a fresh test build after setup, no
 * family) and family (qa/antigravity/templates/state-family.json, without cloud pairing).
 * Numbers follow the runbook: 555-999-xxxx are strangers, 555-010-xxxx are known.
 *
 * Both say yes on every consent page, as a parent finishing setup would, so the home
 * chip reflects missing permissions ("Check me") the way it does on a real phone.
 * Neither has reminders, so no reminder can fire in the middle of an unrelated test;
 * reminder tests add their own.
 */
object DeviceFixtures {
    const val UNKNOWN = "5559998888"
    const val UNKNOWN_2 = "5559997777"
    const val UNKNOWN_3 = "5559996666"
    const val SARAH = "5550101111"
    const val DAVID = "5550102222"
    const val DR_PATEL = "5550103333"
    const val PHARMACY = "5550104444"
    /** Saved in the phone's own Contacts by the tests that need a phone contact ("Pat"). */
    const val PAT = "5550107777"

    /** The lock code the runbook uses. */
    const val CODE = "2580"

    val allConsent: Map<String, Boolean>
        get() = listOf(Features.HOME, Features.PAUSE, Features.CALLS, Features.CHECK_IN, Features.REMINDERS, Features.BATTERY)
            .associateWith { true }

    val soloState: LocalState
        get() = LocalState(
            config = DefaultConfig.config,
            onboardingDone = true,
            consent = allConsent,
            consentAcceptedAt = System.currentTimeMillis(),
        )

    val familyState: LocalState
        get() {
            val base = Fixtures.config
            return LocalState(
                config = base.copy(
                    home = base.home.copy(tiles = base.home.tiles + HomeTile("t9", TileKind.APP, StubApp.NOTES.packageName)),
                    allowedApps = listOf(StubApp.NOTES.packageName),
                    protection = base.protection.copy(
                        callScreening = base.protection.callScreening.copy(
                            allowlist = listOf(AllowedNumber("Pharmacy", "+1 555 010 4444")),
                        ),
                    ),
                    reminders = emptyList(),
                    checkIn = base.checkIn.copy(timezone = TimeZone.getDefault().id),
                ),
                onboardingDone = true,
                consent = allConsent,
                consentAcceptedAt = System.currentTimeMillis(),
            )
        }

    /** The template's "Blood pressure pill" reminder, at [time] ("HH:mm", phone's time zone). */
    fun pillReminder(time: String, notifyFamilyIfMissed: Boolean = true) = Reminder(
        id = "r1",
        title = "Blood pressure pill",
        detail = "One white tablet with water",
        times = listOf(time),
        notifyFamilyIfMissed = notifyFamilyIfMissed,
    )
}
