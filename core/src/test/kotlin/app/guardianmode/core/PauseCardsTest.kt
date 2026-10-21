package app.guardianmode.core

import app.guardianmode.core.PhoneSim.Companion.CHASE
import app.guardianmode.core.PhoneSim.Companion.COINBASE
import app.guardianmode.core.PhoneSim.Companion.ANYDESK
import app.guardianmode.core.PhoneSim.Companion.NOTES
import app.guardianmode.core.PhoneSim.Companion.SARAH
import app.guardianmode.core.PhoneSim.Companion.TARGET
import app.guardianmode.core.PhoneSim.Companion.UNKNOWN
import app.guardianmode.core.PhoneSim.Companion.VENMO
import app.guardianmode.core.PhoneSim.Companion.ZELLE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * qa/antigravity/cases/06-pause-cards.md, the rule parts (docs/SPEC.md F4, bars 3 and 4).
 * Whether the card really covers Chase within 2 seconds is for the device layers.
 */
class PauseCardsTest {

    @Test fun gm_pause_001_bankAppRightAfterAnUnknownCall() {
        val phone = PhoneSim()
        phone.standardCall()
        val actions = phone.open(CHASE, "Chase")
        val card = actions.pauseCards().single()
        assertEquals(AppCategory.BANK, card.category)
        assertEquals("Chase", card.appLabel)
        assertFalse(card.duringCall, "the call is over, so no \"It's OK to hang up.\"")
        val event = actions.shared().single()
        assertEquals(EventType.PAUSE_SHOWN, event.type)
        assertEquals(Severity.NOTICE, event.severity)
        assertEquals(
            mapOf("app" to "Chase", "category" to "BANK", "duringCall" to "false", "minutesSinceCall" to "0"),
            event.data,
        )
    }

    @Test fun gm_pause_002_bankAppDuringTheCallIsUrgent() {
        val phone = PhoneSim()
        phone.ring(UNKNOWN)
        phone.answer()
        phone.talkMinutes(2)
        val actions = phone.open(CHASE, "Chase")
        val card = actions.pauseCards().single()
        assertTrue(card.duringCall)
        assertEquals(0, card.minutesSinceCall)
        val event = actions.shared().single()
        assertEquals(Severity.URGENT, event.severity)
        assertEquals("true", event.data["duringCall"])
        // The call keeps going underneath: the rules still see it as active.
        assertTrue(phone.tracker.activeCall?.unknown == true)
    }

    @Test fun gm_pause_003_everyCategoryGetsItsOwnCard() {
        val cases = listOf(
            Triple(VENMO, "Venmo", AppCategory.PAYMENT),
            Triple(ZELLE, "Zelle", AppCategory.PAYMENT),
            Triple(TARGET, "Target", AppCategory.GIFT_CARD),
            Triple(COINBASE, "Coinbase", AppCategory.CRYPTO),
        )
        val phone = PhoneSim()
        for ((pkg, label, category) in cases) {
            phone.advanceMinutes(60)
            phone.goHome()
            phone.standardCall()
            val actions = phone.open(pkg, label)
            assertEquals(category, actions.pauseCards().single().category, label)
            assertEquals(category.name, actions.shared().single().data["category"], label)
        }
    }

    @Test fun gm_pause_004_cardAt29MinutesNotAt31() {
        val phone = PhoneSim()
        phone.standardCall()
        phone.advanceMinutes(29)
        val at29 = phone.open(CHASE, "Chase")
        assertEquals("29", at29.shared().single().data["minutesSinceCall"])
        assertEquals(29, at29.pauseCards().single().minutesSinceCall)

        phone.goHome()
        phone.standardCall()
        phone.advanceMinutes(31)
        assertTrue(phone.open(CHASE, "Chase").isEmpty())
        assertFalse(phone.engine.isArmed(phone.now))
    }

    @Test fun gm_pause_004_windowIsInclusiveAtExactlyThirtyMinutes() {
        val phone = PhoneSim()
        phone.standardCall()
        phone.advanceMinutes(30)
        assertTrue(phone.engine.isArmed(phone.now))
        phone.advanceMillis(1)
        assertFalse(phone.engine.isArmed(phone.now))
    }

    @Test fun gm_pause_005_unansweredUnknownCallDoesNotArm() {
        val phone = PhoneSim()
        phone.ring(UNKNOWN)
        assertEquals(ScreeningVerdict.SILENCE, phone.lastDecision?.verdict)
        phone.talk(20_000)
        val endActions = phone.hangUp()
        assertTrue(endActions.isEmpty(), "no safe-word card after a missed call")
        assertTrue(phone.open(CHASE, "Chase").isEmpty())
        assertTrue(phone.events.isEmpty())
    }

    @Test fun gm_pause_006_familyCallNeverArmsWhateverTheFormat() {
        for (stored in listOf("5550101111", "+15550101111", "15550101111", "(555) 010-1111", "+1 (555) 010-1111")) {
            val config = PhoneSim.familyConfig().let { c ->
                c.copy(family = c.family.map { if (it.id == "sarah") it.copy(phone = stored) else it })
            }
            val phone = PhoneSim(config)
            phone.ring(SARAH)
            assertEquals(false, phone.lastDecision?.unknown, stored)
            assertEquals(ScreeningVerdict.ALLOW, phone.lastDecision?.verdict, stored)
            phone.answer()
            assertTrue(phone.open(CHASE, "Chase").isEmpty(), "during a call with Sarah ($stored)")
            phone.goHome()
            phone.talk(40_000)
            val end = phone.hangUp()
            assertFalse(end.hasSafeWordCard(), stored)
            assertTrue(phone.open(CHASE, "Chase").isEmpty(), "after a call with Sarah ($stored)")
            assertTrue(phone.events.isEmpty(), stored)
        }
    }

    @Test fun gm_pause_007_phoneContactNeverArmsWithOrWithoutContactsPermission() {
        for (contactsGranted in listOf(true, false)) {
            val phone = PhoneSim(contactsGranted = contactsGranted, phoneContacts = setOf("5550107777"))
            phone.ring("5550107777")
            phone.answer()
            phone.talk(40_000)
            val end = phone.hangUp()
            assertFalse(end.hasSafeWordCard(), "contacts granted=$contactsGranted")
            assertTrue(phone.open(CHASE, "Chase").isEmpty(), "contacts granted=$contactsGranted")
        }
    }

    @Test fun gm_pause_008_allowlistedNumberNeverArms() {
        val phone = PhoneSim(PhoneSim.familyConfig())
        phone.standardCall("5550104444")
        assertEquals(false, phone.lastDecision?.unknown)
        assertTrue(phone.open(CHASE, "Chase").isEmpty())
    }

    /*
     * GM-PAUSE-009 / suspect S-13. The four screening-role x READ_CONTACTS rows, wired the
     * way GuardianBrain wires CallTracker today (screenerSeesContacts = READ_CONTACTS).
     */

    private fun armsAfter(phone: PhoneSim, number: String): Boolean {
        phone.advanceMinutes(120)
        phone.goHome()
        phone.standardCall(number)
        return phone.open(CHASE, "Chase").pauseCards().isNotEmpty()
    }

    @Test fun gm_pause_009_screeningRoleHeldContactsGranted() {
        assertTrue(armsAfter(PhoneSim(PhoneSim.familyConfig(), contactsGranted = true), UNKNOWN))
        assertFalse(armsAfter(PhoneSim(PhoneSim.familyConfig(), contactsGranted = true), SARAH))
    }

    @Test fun gm_pause_009_screeningRoleHeldContactsDenied() {
        assertTrue(armsAfter(PhoneSim(PhoneSim.familyConfig(), contactsGranted = false), UNKNOWN))
        assertFalse(armsAfter(PhoneSim(PhoneSim.familyConfig(), contactsGranted = false), SARAH))
    }

    /**
     * Current behavior, recorded for the report (S-13 confirmed by reading): with no
     * screening role and Contacts granted, no screening result ever arrives, CallTracker
     * treats every unscreened call as a hidden number, and a call from Sarah arms the
     * cards. That breaks spec bar 4. The core contract ("unscreened + screener sees
     * contacts = hidden number") is consistent; the bug is that GuardianBrain passes
     * READ_CONTACTS alone as screenerSeesContacts without checking the screening role
     * (android/.../protection/GuardianBrain.kt, `CallTracker(screenerSeesContacts = ...)`).
     */
    @Test fun gm_pause_009_noScreeningRoleContactsGranted_everyCallCountsAsUnknown() {
        assertTrue(armsAfter(PhoneSim(PhoneSim.familyConfig(), contactsGranted = true, screeningRoleHeld = false), UNKNOWN))
        assertTrue(
            armsAfter(PhoneSim(PhoneSim.familyConfig(), contactsGranted = true, screeningRoleHeld = false), SARAH),
            "S-13: Sarah arms the cards when the screening role isn't held",
        )
    }

    /** Current behavior: with neither, nothing is ever unknown, so the cards never arm (S-13). */
    @Test fun gm_pause_009_noScreeningRoleContactsDenied_nothingEverArms() {
        assertFalse(armsAfter(PhoneSim(PhoneSim.familyConfig(), contactsGranted = false, screeningRoleHeld = false), UNKNOWN))
        assertFalse(armsAfter(PhoneSim(PhoneSim.familyConfig(), contactsGranted = false, screeningRoleHeld = false), SARAH))
    }

    /** GM-PAUSE-010 rule part: Android never screens hidden numbers; with Contacts granted they count as unknown. */
    @Test fun gm_pause_010_hiddenNumberArmsWhenContactsGranted() {
        val phone = PhoneSim(contactsGranted = true)
        phone.ring(null)
        assertEquals(null, phone.lastDecision, "Android doesn't ask the screener about hidden numbers")
        phone.answer()
        phone.talk(40_000)
        phone.hangUp()
        assertTrue(phone.open(CHASE, "Chase").pauseCards().isNotEmpty())
    }

    /** Current behavior: without Contacts, a hidden number can't be told from a contact and doesn't arm. */
    @Test fun gm_pause_010_hiddenNumberDoesNotArmWithoutContacts() {
        val phone = PhoneSim(contactsGranted = false)
        phone.ring(null)
        phone.answer()
        phone.talk(40_000)
        phone.hangUp()
        assertTrue(phone.open(CHASE, "Chase").isEmpty())
    }

    /** S-14 is a product question: calls the parent places are treated as known. This records today's behavior. */
    @Test fun gm_pause_011_callingTheScammerBackDoesNotArm() {
        val phone = PhoneSim()
        phone.dialOut()
        phone.talk(40_000)
        val end = phone.hangUp()
        assertFalse(end.hasSafeWordCard())
        assertTrue(phone.open(CHASE, "Chase").isEmpty(), "S-14: an outgoing call never arms the cards today")
    }

    @Test fun gm_pause_012_callWaitingDuringTheUnknownCallKeepsItArmed() {
        val phone = PhoneSim(PhoneSim.familyConfig())
        phone.ring(UNKNOWN)
        phone.answer()
        phone.talk(20_000)
        // Sarah rings in (call waiting) and is ignored.
        phone.ring(SARAH)
        assertEquals(false, phone.lastDecision?.unknown)
        phone.answer()
        phone.talk(20_000)
        phone.hangUp()
        val card = phone.open(CHASE, "Chase").pauseCards().single()
        assertFalse(card.duringCall)
    }

    @Test fun gm_pause_020_continueLetsThatAppThroughButNotOtherCategories() {
        val phone = PhoneSim()
        phone.standardCall()
        assertTrue(phone.open(CHASE, "Chase").pauseCards().isNotEmpty())
        val outcome = phone.pauseOutcome(CHASE, "Chase", PauseOutcome.CONTINUED)
        val event = outcome.shared().single()
        assertEquals(EventType.PAUSE_CONTINUED, event.type)
        assertEquals("Chase", event.data["app"])
        phone.open(NOTES, "Notes")
        assertTrue(phone.open(CHASE, "Chase").isEmpty(), "no second card for Chase")
        assertEquals(AppCategory.PAYMENT, phone.open(VENMO, "Venmo").pauseCards().single().category)
    }

    @Test fun gm_pause_021_callFamilyFirstIsShared() {
        val phone = PhoneSim(PhoneSim.familyConfig())
        phone.standardCall()
        phone.open(CHASE, "Chase")
        val event = phone.pauseOutcome(CHASE, "Chase", PauseOutcome.CALLED_FAMILY).shared().single()
        assertEquals(EventType.PAUSE_CALLED_FAMILY, event.type)
        assertEquals("Chase", event.data["app"])
        // The call to Sarah the card places is an outgoing call: it neither ends nor resets the window.
        phone.dialOut()
        phone.talkMinutes(3)
        phone.hangUp()
        assertTrue(phone.engine.isArmed(phone.now))
    }

    /** "Go to my home screen" records nothing and doesn't use up the category: Chase asks again next time. */
    @Test fun gm_pause_022_goHomeRecordsNothingAndAsksAgainNextTime() {
        val phone = PhoneSim()
        phone.standardCall()
        assertTrue(phone.open(CHASE, "Chase").pauseCards().isNotEmpty())
        assertTrue(phone.pauseOutcome(CHASE, "Chase", PauseOutcome.CLOSED).isEmpty())
        phone.goHome()
        assertTrue(phone.open(CHASE, "Chase").pauseCards().isNotEmpty(), "asks again after going home from the card")
    }

    @Test fun gm_pause_023_oncePerCategoryPerCall() {
        val phone = PhoneSim()
        phone.standardCall()
        assertTrue(phone.open(VENMO, "Venmo").pauseCards().isNotEmpty())
        phone.pauseOutcome(VENMO, "Venmo", PauseOutcome.CONTINUED)
        assertTrue(phone.open(ZELLE, "Zelle").isEmpty(), "payment apps were already asked about")
        assertEquals(AppCategory.BANK, phone.open(CHASE, "Chase").pauseCards().single().category)
    }

    @Test fun gm_pause_024_newUnknownCallReArmsEverything() {
        val phone = PhoneSim()
        phone.standardCall()
        phone.open(CHASE, "Chase")
        phone.pauseOutcome(CHASE, "Chase", PauseOutcome.CONTINUED)
        phone.goHome()
        phone.advanceMinutes(5)
        phone.standardCall("5559997777")
        assertTrue(phone.open(CHASE, "Chase").pauseCards().isNotEmpty())
    }

    @Test fun gm_pause_030_switchedOffShowsNothingButTheRemoteAlarmStillWorks() {
        val phone = PhoneSim()
        phone.config = phone.config.copy(
            protection = phone.config.protection.copy(pauseTripwires = PauseSettings(enabled = false)),
        )
        phone.standardCall()
        assertTrue(phone.open(CHASE, "Chase").isEmpty())
        assertTrue(phone.events.isEmpty())
        assertTrue(phone.open(ANYDESK, "AnyDesk").remoteWarnings().isNotEmpty())

        // Back on: the next app opened in the window gets its card.
        phone.config = DeviceConfig()
        assertTrue(phone.open(CHASE, "Chase").pauseCards().isNotEmpty())
    }

    @Test fun gm_pause_046_twoRiskyAppsAtOnceEachGetACard() {
        val phone = PhoneSim()
        phone.standardCall()
        assertEquals(AppCategory.BANK, phone.open(CHASE, "Chase").pauseCards().single().category)
        assertEquals(AppCategory.CRYPTO, phone.open(COINBASE, "Coinbase").pauseCards().single().category)
        assertEquals(2, phone.events.count { it.type == EventType.PAUSE_SHOWN })
    }

    /** GM-FAM-042 rule part: protection settings changed by the family apply at once. */
    @Test fun gm_fam_042_dashboardProtectionChangesApplyLive() {
        val phone = PhoneSim()
        phone.config = phone.config.copy(
            protection = phone.config.protection.copy(
                pauseTripwires = PauseSettings(windowMinutes = 10, extraPackages = mapOf("org.hometowncu" to AppCategory.BANK)),
                longCallAlert = LongCallSettings(minutes = 10),
            ),
        )
        phone.ring(UNKNOWN)
        phone.answer()
        val long = phone.talkMinutes(11).shared().single()
        assertEquals(EventType.LONG_UNKNOWN_CALL, long.type)
        assertEquals("10", long.data["minutes"])
        phone.hangUp()
        phone.advanceMinutes(9)
        assertEquals(AppCategory.BANK, phone.open("org.hometowncu", "Hometown CU").pauseCards().single().category)
        phone.goHome()
        phone.advanceMinutes(2)
        assertFalse(phone.engine.isArmed(phone.now), "the 10-minute window has passed")
    }
}
