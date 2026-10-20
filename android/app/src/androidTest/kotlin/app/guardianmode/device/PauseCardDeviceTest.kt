package app.guardianmode.device

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import app.guardianmode.core.EventType
import app.guardianmode.core.LockLevel
import app.guardianmode.core.Severity
import app.guardianmode.support.DeviceFixtures
import app.guardianmode.support.DeviceFixtures.PAT
import app.guardianmode.support.DeviceFixtures.PHARMACY
import app.guardianmode.support.DeviceFixtures.SARAH
import app.guardianmode.support.DeviceFixtures.UNKNOWN
import app.guardianmode.support.DeviceFixtures.UNKNOWN_2
import app.guardianmode.support.DeviceRule
import app.guardianmode.support.DeviceRule.Companion.CARD_BAR_MS
import app.guardianmode.support.EmulatorConsole
import app.guardianmode.support.KnownBug
import app.guardianmode.support.StubApp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * qa/antigravity/cases/06-pause-cards.md on an emulator: real calls through the emulator
 * console, stand-in apps opened from outside Guardian so Usage access has to notice them,
 * cards found on screen. Calls are kept short (a few seconds) unless the case is about
 * the safe-word card, which needs 30 seconds of talk.
 */
@RunWith(AndroidJUnit4::class)
class PauseCardDeviceTest {
    @get:Rule val rule = DeviceRule()

    private fun shortUnknownCall(number: String = UNKNOWN) = rule.answeredCall(number, talkMillis = 3_000)

    /** Opens [app] and returns how long the pause card took, or null if it never came. */
    private fun openAndTimeCard(app: StubApp, timeoutMs: Long = DeviceRule.TIMEOUT): Long? {
        rule.open(app)
        return rule.timeUntil(rule.pauseCard, timeoutMs, rule.lastOpenAt).also {
            Log.i(TAG, "${app.label}: pause card after ${it ?: "never"} ms")
        }
    }

    private fun assertCardFor(app: StubApp, words: String, duringCall: Boolean) {
        assertWithMessage("pause card for ${app.label}").that(openAndTimeCard(app)).isNotNull()
        assertThat(rule.seen(By.text("Did someone on the phone ask you to open ${app.label}?"))).isTrue()
        assertThat(rule.seen(By.textContains("never ask you to $words."))).isTrue()
        assertThat(rule.has(By.textContains("It's OK to hang up."))).isEqualTo(duringCall)
        assertThat(rule.seen(By.text("I'm fine, continue"))).isTrue()
        assertThat(rule.seen(By.text("Go to my home screen"))).isTrue()
    }

    private fun assertNoCardFor(app: StubApp) {
        rule.open(app)
        assertWithMessage("no pause card for ${app.label}").that(rule.staysAway(rule.pauseCard)).isTrue()
    }

    private fun continueAnyway() {
        rule.tap("I'm fine, continue")
        assertThat(rule.waitGone(rule.pauseCard)).isTrue()
    }

    @Test fun gm_pause_001_bankAppAfterUnknownCallShowsCardWithoutHangUpLine() {
        rule.answeredCall(UNKNOWN, talkMillis = 32_000)
        rule.dismissSafeWordCard()

        assertCardFor(StubApp.CHASE, "move money from your bank", duringCall = false)
        assertThat(rule.topPackage()).isEqualTo(rule.pkg) // the card covers Chase
        // Solo: no family member, so no "Call ___ first".
        assertThat(rule.has(By.textStartsWith("Call "))).isFalse()

        val event = rule.waitForEvent(EventType.PAUSE_SHOWN)!!
        assertThat(event.severity).isEqualTo(Severity.NOTICE)
        assertThat(event.data).containsAtLeast("app", "Chase", "category", "BANK", "duringCall", "false", "minutesSinceCall", "0")
    }

    @Test fun gm_pause_002_bankAppDuringCallSaysItsOkToHangUpAndCallKeepsGoing() {
        rule.startAnsweredCall(UNKNOWN)

        assertCardFor(StubApp.CHASE, "move money from your bank", duringCall = true)
        assertThat(rule.seen(By.text("It's OK to hang up. Real banks, police, Medicare and tech companies never ask you to move money from your bank."))).isTrue()

        val event = rule.waitForEvent(EventType.PAUSE_SHOWN)!!
        assertThat(event.severity).isEqualTo(Severity.URGENT)
        assertThat(event.data).containsAtLeast("duringCall", "true", "minutesSinceCall", "0")
        // The call goes on underneath the card (telephony still off-hook; the modem's own
        // listing is only logged, its wording differs between emulator versions).
        assertThat(rule.callState()).isEqualTo(2)
        Log.i(TAG, "GM-PAUSE-002 gsm list under the card: ${EmulatorConsole.list().trim()}")
        rule.hangUp(UNKNOWN)
    }

    @Test fun gm_pause_003_everyCategoryGetsItsOwnWords() {
        val cases = listOf(
            Triple(StubApp.VENMO, "send money", "PAYMENT"),
            Triple(StubApp.ZELLE, "send money", "PAYMENT"),
            Triple(StubApp.TARGET, "buy gift cards", "GIFT_CARD"),
            Triple(StubApp.COINBASE, "send crypto or Bitcoin", "CRYPTO"),
        )
        for ((app, words, category) in cases) {
            shortUnknownCall()
            assertCardFor(app, words, duringCall = false)
            val shown = rule.waitUntil { rule.events(EventType.PAUSE_SHOWN).any { it.data["app"] == app.label } }
            assertWithMessage("PAUSE_SHOWN for ${app.label}").that(shown).isTrue()
            assertThat(rule.events(EventType.PAUSE_SHOWN).first { it.data["app"] == app.label }.data["category"]).isEqualTo(category)
            continueAnyway()
            rule.goHome()
        }
    }

    @Test fun gm_pause_005_unansweredUnknownCallDoesNotArm() {
        assertThat(rule.ring(UNKNOWN)).isTrue()
        Thread.sleep(8_000) // rings (silently) and is never answered
        rule.hangUp(UNKNOWN)
        assertNoCardFor(StubApp.CHASE)
        assertThat(rule.events(EventType.PAUSE_SHOWN)).isEmpty()
    }

    @Test fun gm_pause_006_gm_call_012_familyCallNeverArmsNorShowsSafeWord() {
        rule.setState(DeviceFixtures.familyState)
        // During the call...
        rule.startAnsweredCall(SARAH)
        assertNoCardFor(StubApp.CHASE)
        rule.goHome()
        // ...and after 30+ seconds of talk (long enough for the safe-word card after a stranger).
        Thread.sleep(30_000)
        rule.hangUp(SARAH)
        assertThat(rule.staysAway(By.text(DeviceRule.SAFE_WORD_HEADLINE), 4_000)).isTrue()
        assertNoCardFor(StubApp.CHASE)
        assertThat(rule.state.sharedEvents).isEmpty()
    }

    @Test fun gm_pause_006_familyNumberMatchingIgnoresFormatting() {
        for (written in listOf("+15550101111", "15550101111", "(555) 010-1111")) {
            val family = DeviceFixtures.familyState
            rule.setState(family.copy(config = family.config.copy(family = family.config.family.map { if (it.id == "sarah") it.copy(phone = written) else it })))
            rule.answeredCall(SARAH, talkMillis = 3_000)
            rule.open(StubApp.CHASE)
            assertWithMessage("Sarah written as $written").that(rule.staysAway(rule.pauseCard)).isTrue()
            rule.goHome()
        }
        assertThat(rule.state.sharedEvents).isEmpty()
    }

    // Revoking READ_CONTACTS (the case's second half) kills Guardian's process, which is
    // where these tests run; that half is left to the host-side script.
    @Test fun gm_pause_007_phoneContactNeverArms() {
        rule.addPhoneContact("Pat", PAT)
        shortUnknownCall(PAT)
        assertNoCardFor(StubApp.CHASE)
        assertThat(rule.state.sharedEvents).isEmpty()
    }

    @Test fun gm_pause_008_allowlistedNumberNeverArms() {
        rule.setState(DeviceFixtures.familyState)
        shortUnknownCall(PHARMACY)
        assertNoCardFor(StubApp.CHASE)
    }

    @Test fun gm_pause_009_roleHeldContactsGrantedUnknownArmsFamilyDoesNot() {
        rule.setState(DeviceFixtures.familyState)
        rule.answeredCall(SARAH, talkMillis = 3_000)
        assertNoCardFor(StubApp.CHASE)
        rule.goHome()
        shortUnknownCall()
        assertThat(openAndTimeCard(StubApp.CHASE)).isNotNull()
    }

    @Test fun gm_pause_009_screeningRoleNotHeldUnknownCallStillArms() {
        rule.shell("cmd role remove-role-holder android.app.role.CALL_SCREENING ${rule.pkg}")
        shortUnknownCall()
        // Contacts is granted, so an unscreened call counts as unknown (CallTracker).
        assertThat(openAndTimeCard(StubApp.CHASE)).isNotNull()
    }

    /** S-13: without the screening role and with Contacts on, every call counts as unknown. */
    @KnownBug("S-13")
    @Test fun gm_pause_009_screeningRoleNotHeldFamilyCallDoesNotArm() {
        rule.setState(DeviceFixtures.familyState)
        rule.shell("cmd role remove-role-holder android.app.role.CALL_SCREENING ${rule.pkg}")
        rule.answeredCall(SARAH, talkMillis = 3_000)
        assertNoCardFor(StubApp.CHASE) // spec bar 4: zero false alarms from family calls
    }

    /** S-14: today a call the parent places never arms. The case records it; this pins the current, documented behavior. */
    @Test fun gm_pause_011_callingTheStrangerBackDoesNotArmToday() {
        rule.placeCall(UNKNOWN)
        Thread.sleep(3_000)
        rule.hangUp(UNKNOWN)
        assertNoCardFor(StubApp.CHASE)
    }

    @Test fun gm_pause_012_secondCallDuringUnknownCallStaysArmed() {
        rule.startAnsweredCall(UNKNOWN)
        EmulatorConsole.incomingCall(SARAH) // call waiting, ignored
        Thread.sleep(4_000)
        rule.hangUpEverything()
        Thread.sleep(DeviceRule.SETTLE)
        assertThat(openAndTimeCard(StubApp.CHASE)).isNotNull()
    }

    @Test fun gm_pause_020_continueLetsThatAppThroughButNotOtherCategories() {
        shortUnknownCall()
        assertThat(openAndTimeCard(StubApp.CHASE)).isNotNull()
        continueAnyway()
        assertThat(rule.waitForPackage(StubApp.CHASE.packageName)).isTrue()
        val continued = rule.waitForEvent(EventType.PAUSE_CONTINUED)!!
        assertThat(continued.data["app"]).isEqualTo("Chase")

        rule.open(StubApp.NOTES)
        assertThat(rule.waitForPackage(StubApp.NOTES.packageName)).isTrue()
        assertNoCardFor(StubApp.CHASE)
        assertThat(openAndTimeCard(StubApp.VENMO)).isNotNull()
    }

    @Test fun gm_pause_021_callSarahFirstDialsSarahAndClosesCard() {
        rule.setState(DeviceFixtures.familyState)
        shortUnknownCall()
        assertThat(openAndTimeCard(StubApp.CHASE)).isNotNull()
        val tapped = System.currentTimeMillis()
        rule.tap("Call Sarah first")
        assertThat(rule.waitGone(rule.pauseCard)).isTrue()
        assertThat(rule.waitForEvent(EventType.PAUSE_CALLED_FAMILY)!!.data["app"]).isEqualTo("Chase")
        assertWithMessage("a call to Sarah").that(rule.placedCallTo(SARAH, tapped)).isTrue()
    }

    @Test fun gm_pause_021_callSarahFirstDuringUnknownCallDoesSomething() {
        rule.setState(DeviceFixtures.familyState)
        rule.startAnsweredCall(UNKNOWN)
        assertThat(openAndTimeCard(StubApp.CHASE)).isNotNull()
        rule.tap("Call Sarah first")
        // The case asks to record what Android does (hold and dial, or refuse); it must not
        // silently do nothing: the card closes and the family call is recorded or placed.
        assertThat(rule.waitGone(rule.pauseCard)).isTrue()
        assertThat(rule.waitForEvent(EventType.PAUSE_CALLED_FAMILY)).isNotNull()
        Log.i(TAG, "GM-PAUSE-021 during call: gsm list = ${EmulatorConsole.list().trim()}, top = ${rule.topPackage()}")
    }

    @Test fun gm_pause_022_goToHomeScreenGoesHomeAndRecordsNothing() {
        shortUnknownCall()
        assertThat(openAndTimeCard(StubApp.CHASE)).isNotNull()
        rule.tap("Go to my home screen")
        assertThat(rule.waitFor(rule.shieldChip)).isTrue()
        assertThat(rule.topPackage()).isEqualTo(rule.pkg)
        Thread.sleep(1_000)
        assertThat(rule.sharedTypes()).containsExactly("PAUSE_SHOWN")
        // S-15: record whether a second open in the same window asks again.
        rule.open(StubApp.CHASE)
        Log.i(TAG, "GM-PAUSE-022 (S-15) second open after Go home: card=${!rule.staysAway(rule.pauseCard, 4_000)}")
    }

    @Test fun gm_pause_023_oncePerCategoryPerCall() {
        shortUnknownCall()
        assertThat(openAndTimeCard(StubApp.VENMO)).isNotNull()
        continueAnyway()
        assertNoCardFor(StubApp.ZELLE) // payment was already asked about for this call
        assertThat(openAndTimeCard(StubApp.CHASE)).isNotNull()
    }

    @Test fun gm_pause_024_newUnknownCallRearmsEverything() {
        shortUnknownCall()
        assertThat(openAndTimeCard(StubApp.CHASE)).isNotNull()
        continueAnyway()
        rule.goHome()
        shortUnknownCall(UNKNOWN_2)
        assertThat(openAndTimeCard(StubApp.CHASE)).isNotNull()
    }

    @Test fun gm_pause_025_backClosesCardWithoutLeavingOneBehind() {
        shortUnknownCall()
        assertThat(openAndTimeCard(StubApp.CHASE)).isNotNull()
        rule.back()
        assertThat(rule.waitGone(rule.pauseCard)).isTrue()
        assertThat(rule.waitUntil { rule.topPackage() in setOf(StubApp.CHASE.packageName, rule.pkg) }).isTrue()
        Log.i(TAG, "GM-PAUSE-025 Back landed on ${rule.topPackage()}")
        assertThat(rule.staysAway(rule.pauseCard, 3_000)).isTrue()
    }

    @Test fun gm_pause_030_pauseCardsSwitchedOffButRemoteAlarmStillWorks() {
        rule.openTransparency()
        rule.scrollAndTap(By.desc("Pause before sending money"))
        assertThat(rule.waitUntil { !rule.state.config.protection.pauseTripwires.enabled }).isTrue()

        shortUnknownCall()
        assertNoCardFor(StubApp.CHASE)
        assertThat(rule.events(EventType.PAUSE_SHOWN)).isEmpty()

        rule.installStub(StubApp.ANYDESK)
        assertThat(rule.waitTextContains("AnyDesk can let someone control your phone")).isTrue()

        rule.openTransparency()
        rule.scrollAndTap(By.desc("Pause before sending money"))
        assertThat(rule.waitUntil { rule.state.config.protection.pauseTripwires.enabled }).isTrue()
        shortUnknownCall(UNKNOWN_2)
        assertThat(openAndTimeCard(StubApp.CHASE)).isNotNull()
    }

    @Test fun gm_pause_031_pauseEverythingStopsCardsAndRecordsNothing() {
        rule.openTransparency()
        rule.tap("Pause everything for 1 hour")
        assertThat(rule.waitUntil { rule.state.isPaused(System.currentTimeMillis()) }).isTrue()

        shortUnknownCall()
        assertNoCardFor(StubApp.CHASE)
        rule.installStub(StubApp.ANYDESK)
        assertThat(rule.staysAway(By.textContains("can let someone control your phone"))).isTrue()
        rule.open(StubApp.ANYDESK)
        assertThat(rule.staysAway(By.textContains("can let someone control your phone"))).isTrue()
        assertThat(rule.state.sharedEvents).isEmpty()

        rule.openTransparency()
        rule.tap("Turn protection back on")
        assertThat(rule.waitUntil { !rule.state.isPaused(System.currentTimeMillis()) }).isTrue()
        shortUnknownCall(UNKNOWN_2)
        assertThat(openAndTimeCard(StubApp.CHASE)).isNotNull()
    }

    /**
     * S-03: at Guided and Locked, "Pause everything" needs the code in the app, but the
     * notification's "Pause for 1 hour" pauses straight away (Receivers.kt ACTION_PAUSE_HOUR).
     * Also GM-SCAM-005 step 1 and GM-TRAN-010's notification half.
     */
    @KnownBug("S-03")
    @Test fun gm_pause_032_gm_scam_005_gm_tran_010_notificationPauseNeedsCodeAtGuidedAndLocked() {
        for (level in listOf(LockLevel.GUIDED, LockLevel.LOCKED)) {
            rule.setLock(level)
            val action = rule.notificationAction("Pause for 1 hour")
            assertWithMessage("Pause for 1 hour on Guardian's notification").that(action).isNotNull()
            action!!.click()
            Thread.sleep(2_000)
            assertWithMessage("$level: pausing from the notification asks for the code").that(rule.codeAsked(3_000)).isTrue()
            assertWithMessage("$level: protection is not paused without the code")
                .that(rule.state.isPaused(System.currentTimeMillis())).isFalse()
            rule.updateState { it.copy(pausedUntil = 0) }
            rule.goHome()
        }
        // And the scam sequence still gets a card.
        rule.setLock(LockLevel.GUIDED)
        shortUnknownCall()
        assertThat(openAndTimeCard(StubApp.CHASE)).isNotNull()
    }

    @Test fun gm_pause_040_gm_perf_003_cardWithinTwoSecondsEveryTime() {
        val times = mutableListOf<Long>()
        repeat(RUNS) { i ->
            shortUnknownCall(if (i % 2 == 0) UNKNOWN else UNKNOWN_2)
            val t = openAndTimeCard(StubApp.CHASE, timeoutMs = 10_000)
            assertWithMessage("run ${i + 1}: card appeared").that(t).isNotNull()
            times += t!!
            continueAnyway()
            rule.goHome()
        }
        // With the screen just turned on: call ends, screen off, then the parent unlocks and opens Chase.
        shortUnknownCall()
        rule.sleepScreen()
        Thread.sleep(3_000)
        rule.wake()
        val afterWake = openAndTimeCard(StubApp.CHASE, timeoutMs = 10_000)
        assertWithMessage("card after screen on").that(afterWake).isNotNull()

        val sorted = times.sorted()
        Log.i(TAG, "GM-PAUSE-040 pause card ms: min=${sorted.first()} median=${sorted[sorted.size / 2]} max=${sorted.last()} all=$times; after screen-on=$afterWake")
        // The bar is 2 s every time on a phone. A CI emulator shares its CPU and now and then
        // stalls for a second or more, so here 9 runs of 10 must make the 2 s bar and none may
        // take more than twice that; the log line carries every number for the report.
        val overBar = times.count { it > CARD_BAR_MS }
        assertWithMessage("runs over $CARD_BAR_MS ms (of $RUNS): $times").that(overBar).isAtMost(1)
        times.forEachIndexed { i, t -> assertWithMessage("run ${i + 1} latency (ms)").that(t).isAtMost(2 * CARD_BAR_MS) }
        assertWithMessage("latency after screen-on (ms)").that(afterWake).isAtMost(2 * CARD_BAR_MS)
    }

    @Test fun gm_pause_041_withoutOverlayHomeAppStillShowsCard() {
        rule.shell("appops set ${rule.pkg} SYSTEM_ALERT_WINDOW deny")
        shortUnknownCall()
        // Assumption: instrumented processes may start activities from the background
        // anyway, so this proves the code path (home role -> direct start), not Android's
        // background-start rule for a home app; the host-side script covers that.
        assertThat(openAndTimeCard(StubApp.CHASE)).isNotNull()
    }

    @Test fun gm_pause_041_withoutOverlayOrHomeRoleNotificationOpensCard() {
        rule.shell("appops set ${rule.pkg} SYSTEM_ALERT_WINDOW deny")
        rule.shell("cmd role remove-role-holder android.app.role.HOME ${rule.pkg}")
        shortUnknownCall()
        rule.open(StubApp.CHASE)
        assertThat(rule.staysAway(rule.pauseCard, 3_000)).isTrue()

        rule.device.openNotification()
        assertThat(rule.waitText("Hold on a minute")).isTrue()
        assertThat(rule.seen(By.textContains("Did someone on the phone ask you to open Chase? Tap to talk it over with family first."))).isTrue()
        rule.tap("Hold on a minute")
        assertThat(rule.waitFor(rule.pauseCard)).isTrue()
    }

    // GM-PAUSE-042 (process killed) can't run here: `am kill` / `force-stop` on Guardian
    // would kill these tests too. It stays with the host-side script.

    @Test fun gm_pause_044_withoutUsageAccessNoCardAndChipSaysCheckMe() {
        rule.shell("appops set ${rule.pkg} GET_USAGE_STATS deny")
        shortUnknownCall()
        assertNoCardFor(StubApp.CHASE)
        rule.goHome()
        assertThat(rule.waitFor(By.desc("Check me. What my family can see."))).isTrue()
    }

    private companion object {
        const val TAG = "PauseCardDeviceTest"
        const val RUNS = 10
    }
}
