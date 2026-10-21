package app.guardianmode.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** qa/antigravity/cases/10-transparency-and-pause.md, the parts decided by core/Transparency.kt (docs/SPEC.md F12, bar 5). */
class TransparencyTest {

    @Test fun gm_tran_003_sevenItemsInOrderWithSharedAndNeverSharedText() {
        val items = Transparency.items(DeviceConfig())
        assertEquals(
            listOf(
                "Pause before sending money",
                "Remote-control app alarm",
                "Long calls with unknown numbers",
                "Unknown caller screening",
                "Daily \"I'm OK\"",
                "Help me button",
                "Reminders",
            ),
            items.map { it.title },
        )
        items.forEach {
            assertTrue(it.whatIsShared.isNotBlank(), it.key)
            assertTrue(it.whatIsNeverShared.isNotBlank(), it.key)
        }
        assertEquals(items.size, items.map { it.key }.toSet().size, "keys are unique")
    }

    @Test fun gm_tran_003_defaultsAreOnExceptRemindersWithNoReminders() {
        val enabled = Transparency.items(DeviceConfig()).associate { it.key to it.enabled }
        assertEquals(
            mapOf(
                "pause" to true, "remoteAccess" to true, "longCall" to true, "screening" to true,
                "checkIn" to true, "help" to true, "reminders" to false,
            ),
            enabled,
        )
    }

    @Test fun gm_tran_003_eachLineFollowsTheLiveSettings() {
        val config = DeviceConfig(
            protection = ProtectionSettings(
                pauseTripwires = PauseSettings(enabled = false),
                remoteAccessAlarm = RemoteAccessSettings(enabled = false),
                longCallAlert = LongCallSettings(enabled = false, minutes = 45),
                callScreening = ScreeningSettings(mode = ScreeningMode.OFF),
            ),
            checkIn = CheckInSettings(enabled = false, deadline = "09:30"),
            reminders = listOf(Reminder("r", "Blood pressure pill", times = listOf("08:00"))),
        )
        val items = Transparency.items(config).associateBy { it.key }
        listOf("pause", "remoteAccess", "longCall", "screening", "checkIn").forEach { assertFalse(items.getValue(it).enabled, it) }
        assertTrue(items.getValue("help").enabled, "Help me has no switch")
        assertTrue(items.getValue("reminders").enabled, "Reminders is on once a reminder exists")
        assertEquals("That a call with an unknown number went past 45 minutes.", items.getValue("longCall").whatIsShared)
        assertEquals("Whether your phone has been used today, by 09:30.", items.getValue("checkIn").whatIsShared)
        assertEquals("Nothing.", items.getValue("reminders").whatIsShared)
    }

    @Test fun gm_tran_003_remindersSharedTextChangesWithNotifyIfMissed() {
        val quiet = Reminder("a", "Vitamins", times = listOf("09:00"))
        val told = Reminder("b", "Blood pressure pill", times = listOf("08:00"), notifyFamilyIfMissed = true)
        assertEquals("Nothing.", Transparency.items(DeviceConfig(reminders = listOf(quiet))).single { it.key == "reminders" }.whatIsShared)
        assertEquals(
            "Whether you confirmed reminders your family asked to hear about.",
            Transparency.items(DeviceConfig(reminders = listOf(quiet, told))).single { it.key == "reminders" }.whatIsShared,
        )
    }

    @Test fun gm_tran_003_screeningIsOnInBothSilenceAndVoicemailModes() {
        for (mode in ScreeningMode.entries) {
            val item = Transparency.items(DeviceConfig(protection = ProtectionSettings(callScreening = ScreeningSettings(mode = mode))))
                .single { it.key == "screening" }
            assertEquals(mode != ScreeningMode.OFF, item.enabled, mode.name)
            assertEquals("Nothing. Screening happens on your phone only.", item.whatIsShared)
        }
    }

    @Test fun gm_tran_005_neverSharedListIsExact() {
        assertEquals(
            listOf(
                "Your messages and texts",
                "What is said on calls",
                "Your location",
                "Your photos, unless you send one with Help",
                "Your passwords or bank details",
            ),
            Transparency.NEVER_COLLECTED,
        )
    }

    /** Spec bar 9: copy to the parent never says "scam", "fraud", "danger" or "alert". */
    @Test fun gm_copy_001_transparencyLinesAvoidBannedWords() {
        val texts = Transparency.items(DeviceConfig(reminders = listOf(Reminder("r", "x", notifyFamilyIfMissed = true)))).flatMap {
            listOf(it.title, it.whatIsShared, it.whatIsNeverShared)
        } + Transparency.NEVER_COLLECTED
        for (t in texts) {
            for (word in listOf("scam", "fraud", "danger", "alert")) assertFalse(word in t.lowercase(), "\"$t\" says $word")
        }
    }
}
