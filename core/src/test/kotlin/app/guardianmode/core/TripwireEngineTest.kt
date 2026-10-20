package app.guardianmode.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TripwireEngineTest {
    private val m = TripwireEngine.MINUTE
    private var ids = 0
    private var config = DeviceConfig()
    private val engine = TripwireEngine({ config }, newId = { "e${ids++}" })
    private val tracker = CallTracker()

    private fun unknownCall(start: Long, talkMinutes: Long): List<GuardianAction> {
        tracker.onScreened("5559998888", unknown = true, at = start)
        val actions = mutableListOf<GuardianAction>()
        (tracker.onCallState(PhoneCallState.RINGING, start + 500) +
            tracker.onCallState(PhoneCallState.OFFHOOK, start + 3_000)).forEach { actions += engine.onCall(it, start + 3_000) }
        val end = start + 3_000 + talkMinutes * m
        tracker.onCallState(PhoneCallState.IDLE, end).forEach { actions += engine.onCall(it, end) }
        return actions
    }

    private fun events(actions: List<GuardianAction>) = actions.filterIsInstance<GuardianAction.Share>().map { it.event }

    @Test fun `gm_pause_001 bank app shortly after an unknown call shows a pause card`() {
        unknownCall(0, 5)
        val actions = engine.onForeground("com.chase.sig.android", "Chase", 10 * m)
        val card = actions.filterIsInstance<GuardianAction.ShowPauseCard>().single()
        assertEquals(AppCategory.BANK, card.category)
        assertEquals(4, card.minutesSinceCall)
        val event = events(actions).single()
        assertEquals(EventType.PAUSE_SHOWN, event.type)
        assertEquals(Severity.NOTICE, event.severity)
    }

    @Test fun `gm_pause_004 no pause card without a recent unknown call`() {
        assertTrue(engine.onForeground("com.chase.sig.android", "Chase", 0).isEmpty())
        unknownCall(0, 5)
        assertTrue(engine.onForeground("com.venmo", "Venmo", 5 * m + 31 * m).isEmpty())
    }

    @Test fun `gm_pause_002 pause during the call itself is urgent`() {
        tracker.onScreened("5559998888", unknown = true, at = 0)
        tracker.onCallState(PhoneCallState.RINGING, 100).forEach { engine.onCall(it, 100) }
        tracker.onCallState(PhoneCallState.OFFHOOK, 1_000).forEach { engine.onCall(it, 1_000) }
        val actions = engine.onForeground("com.squareup.cash", "Cash App", 2 * m)
        assertTrue(actions.filterIsInstance<GuardianAction.ShowPauseCard>().single().duringCall)
        assertEquals(Severity.URGENT, events(actions).single().severity)
    }

    @Test fun `gm_pause_020 gm_pause_023 pause card shows once per category per call and respects continue`() {
        unknownCall(0, 5)
        assertTrue(engine.onForeground("com.chase.sig.android", "Chase", 6 * m).isNotEmpty())
        engine.onForeground(LAUNCHER, "Home", 7 * m)
        assertTrue(engine.onForeground("com.chase.sig.android", "Chase", 8 * m).isEmpty())
        val other = engine.onForeground("com.coinbase.android", "Coinbase", 9 * m)
        assertTrue(other.any { it is GuardianAction.ShowPauseCard })
        val outcome = engine.onPauseOutcome("com.coinbase.android", "Coinbase", PauseOutcome.CONTINUED, 9 * m)
        assertEquals(EventType.PAUSE_CONTINUED, events(outcome).single().type)
    }

    @Test fun `gm_pause_042 gm_pause_043 the window after a call survives Guardian being restarted`() {
        unknownCall(0, 5)
        val saved = engine.lastUnknownCallEndedAt
        // A fresh engine, as after Android killed the app or the phone restarted.
        val restarted = TripwireEngine({ config }, newId = { "r${ids++}" }, restoredCallEndedAt = saved)
        assertTrue(restarted.onForeground("com.chase.sig.android", "Chase", 12 * m).any { it is GuardianAction.ShowPauseCard })
        val late = TripwireEngine({ config }, newId = { "l${ids++}" }, restoredCallEndedAt = saved)
        assertTrue(late.onForeground("com.chase.sig.android", "Chase", 40 * m).isEmpty())
    }

    @Test fun `gm_call_003 long call is urgent after a bank app even with pause cards off`() {
        config = config.copy(protection = config.protection.copy(pauseTripwires = config.protection.pauseTripwires.copy(enabled = false)))
        tracker.onScreened("5559998888", unknown = true, at = 0)
        tracker.onCallState(PhoneCallState.RINGING, 100).forEach { engine.onCall(it, 100) }
        tracker.onCallState(PhoneCallState.OFFHOOK, 1_000).forEach { engine.onCall(it, 1_000) }
        assertTrue(engine.onForeground("com.chase.sig.android", "Chase", 2 * m).isEmpty())
        val long = events(engine.onTick(25 * m)).single()
        assertEquals(EventType.LONG_UNKNOWN_CALL, long.type)
        assertEquals(Severity.URGENT, long.severity)
    }

    @Test fun `gm_pause_022 going home from a pause card asks again next time`() {
        unknownCall(0, 5)
        assertTrue(engine.onForeground("com.chase.sig.android", "Chase", 6 * m).isNotEmpty())
        engine.onPauseOutcome("com.chase.sig.android", "Chase", PauseOutcome.CLOSED, 6 * m)
        engine.onForeground(LAUNCHER, "Home", 7 * m)
        assertTrue(engine.onForeground("com.chase.sig.android", "Chase", 8 * m).any { it is GuardianAction.ShowPauseCard })
    }

    @Test fun `gm_pause_024 a new unknown call re-arms dismissed tripwires`() {
        unknownCall(0, 5)
        engine.onForeground("com.chase.sig.android", "Chase", 6 * m)
        engine.onForeground(LAUNCHER, "Home", 7 * m)
        unknownCall(20 * m, 3)
        assertTrue(engine.onForeground("com.chase.sig.android", "Chase", 25 * m).any { it is GuardianAction.ShowPauseCard })
    }

    @Test fun `gm_pause_005 disabled categories and missed calls do not trigger`() {
        config = DeviceConfig(protection = ProtectionSettings(pauseTripwires = PauseSettings(categories = setOf(AppCategory.CRYPTO))))
        unknownCall(0, 5)
        assertTrue(engine.onForeground("com.chase.sig.android", "Chase", 6 * m).isEmpty())

        val fresh = TripwireEngine({ DeviceConfig() }, newId = { "x" })
        val t = CallTracker()
        t.onScreened("5559998888", unknown = true, at = 0)
        t.onCallState(PhoneCallState.RINGING, 100).forEach { fresh.onCall(it, 100) }
        t.onCallState(PhoneCallState.IDLE, 30_000).forEach { fresh.onCall(it, 30_000) }
        assertTrue(fresh.onForeground("com.chase.sig.android", "Chase", m).isEmpty())
    }

    @Test fun `gm_fam_042 family-added package triggers like a built-in one`() {
        config = DeviceConfig(protection = ProtectionSettings(pauseTripwires = PauseSettings(extraPackages = mapOf("org.localcu.mobile" to AppCategory.BANK))))
        unknownCall(0, 2)
        assertTrue(engine.onForeground("org.localcu.mobile", "Local CU", 3 * m).any { it is GuardianAction.ShowPauseCard })
    }

    @Test fun `gm_racc_001 remote access install always alarms`() {
        val actions = engine.onPackageInstalled("com.anydesk.anydeskandroid", "AnyDesk", controlCapable = true, now = 0)
        assertIs<GuardianAction.ShowRemoteAccessWarning>(actions[0])
        assertEquals(EventType.REMOTE_ACCESS_INSTALLED, events(actions).single().type)
        assertEquals(Severity.URGENT, events(actions).single().severity)
    }

    @Test fun `trusted remote access app is silent`() {
        config = DeviceConfig(protection = ProtectionSettings(remoteAccessAlarm = RemoteAccessSettings(trustedPackages = setOf("com.teamviewer.quicksupport.market"))))
        assertTrue(engine.onPackageInstalled("com.teamviewer.quicksupport.market", "TeamViewer", true, 0).isEmpty())
        assertTrue(engine.onForeground("com.teamviewer.quicksupport.market", "TeamViewer", 0).isEmpty())
    }

    @Test fun `gm_racc_012 gm_racc_013 unknown app that can control the screen is flagged`() {
        val quiet = engine.onPackageInstalled("com.example.flashlight", "Flashlight", controlCapable = true, now = 0)
        assertEquals(EventType.CONTROL_APP_INSTALLED, events(quiet).single().type)
        assertEquals(Severity.NOTICE, events(quiet).single().severity)
        assertTrue(engine.onPackageInstalled("com.example.game", "Game", controlCapable = false, now = 0).isEmpty())
    }

    @Test fun `gm_racc_005 opening a remote access app warns but not on every switch`() {
        val first = engine.onForeground("com.anydesk.anydeskandroid", "AnyDesk", 0)
        assertEquals(EventType.REMOTE_ACCESS_OPENED, events(first).single().type)
        engine.onForeground(LAUNCHER, "Home", m)
        assertTrue(engine.onForeground("com.anydesk.anydeskandroid", "AnyDesk", 2 * m).isEmpty())
        engine.onForeground(LAUNCHER, "Home", 3 * m)
        assertTrue(engine.onForeground("com.anydesk.anydeskandroid", "AnyDesk", 15 * m).isNotEmpty())
    }

    @Test fun `gm_call_001 long unknown call is shared once without any number`() {
        tracker.onScreened("+1 555 999 8888", unknown = true, at = 0)
        tracker.onCallState(PhoneCallState.RINGING, 0).forEach { engine.onCall(it, 0) }
        tracker.onCallState(PhoneCallState.OFFHOOK, 0).forEach { engine.onCall(it, 0) }
        assertTrue(engine.onTick(19 * m).isEmpty())
        val alert = events(engine.onTick(20 * m)).single()
        assertEquals(EventType.LONG_UNKNOWN_CALL, alert.type)
        assertEquals(null, alert.data["number"])
        assertEquals("20", alert.data["minutes"])
        assertEquals("true", alert.data["ongoing"])
        assertTrue(engine.onTick(25 * m).isEmpty())
        val endActions = tracker.onCallState(PhoneCallState.IDLE, 30 * m).flatMap { engine.onCall(it, 30 * m) }
        assertTrue(events(endActions).isEmpty())
    }

    @Test fun `gm_call_003 long call that involved a money app is urgent`() {
        tracker.onScreened("5559998888", unknown = true, at = 0)
        tracker.onCallState(PhoneCallState.RINGING, 0).forEach { engine.onCall(it, 0) }
        tracker.onCallState(PhoneCallState.OFFHOOK, 0).forEach { engine.onCall(it, 0) }
        engine.onForeground("com.anydesk.anydeskandroid", "AnyDesk", m)
        assertEquals(Severity.URGENT, events(engine.onTick(21 * m)).single().severity)
    }

    @Test fun `gm_call_010 gm_call_011 safe word card follows a real conversation only`() {
        assertTrue(unknownCall(0, 2).contains(GuardianAction.ShowSafeWordCard))
        val brief = TripwireEngine({ DeviceConfig() }, newId = { "x" })
        val t = CallTracker()
        t.onScreened("5559998888", unknown = true, at = 0)
        val acts = (t.onCallState(PhoneCallState.RINGING, 0) + t.onCallState(PhoneCallState.OFFHOOK, 0) + t.onCallState(PhoneCallState.IDLE, 10_000))
            .flatMap { brief.onCall(it, 10_000) }
        assertTrue(acts.none { it == GuardianAction.ShowSafeWordCard })
    }

    @Test fun `gm_pause_006 gm_call_004 known callers never arm anything`() {
        val t = CallTracker()
        (t.onCallState(PhoneCallState.RINGING, 0) + t.onCallState(PhoneCallState.OFFHOOK, 0) + t.onCallState(PhoneCallState.IDLE, 30 * m))
            .forEach { assertTrue(engine.onCall(it, 30 * m).isEmpty()) }
        assertTrue(engine.onForeground("com.chase.sig.android", "Chase", 31 * m).isEmpty())
    }

    private companion object {
        const val LAUNCHER = "app.guardianmode"
    }
}
