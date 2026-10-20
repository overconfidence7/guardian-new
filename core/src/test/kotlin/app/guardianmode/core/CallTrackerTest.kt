package app.guardianmode.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallTrackerTest {
    @Test fun `screened unknown call is tracked through answer and hang-up`() {
        val t = CallTracker()
        t.onScreened("5559998888", unknown = true, at = 1_000)
        val started = t.onCallState(PhoneCallState.RINGING, 1_500).single()
        assertIs<CallUpdate.Started>(started)
        assertTrue(started.session.unknown)
        val answered = t.onCallState(PhoneCallState.OFFHOOK, 5_000).single()
        assertIs<CallUpdate.Answered>(answered)
        val ended = t.onCallState(PhoneCallState.IDLE, 65_000).single()
        assertIs<CallUpdate.Ended>(ended)
        assertEquals(60_000, ended.session.talkMillis(99_999))
        assertNull(t.activeCall)
    }

    @Test fun `ringing without a screening result is a known contact`() {
        val t = CallTracker()
        val started = t.onCallState(PhoneCallState.RINGING, 0).single()
        assertFalse(started.session.unknown)
    }

    @Test fun `gm_pause_010 unscreened call is a hidden number when the screener sees contacts`() {
        val t = CallTracker(screenerSeesContacts = { true })
        assertTrue(t.onCallState(PhoneCallState.RINGING, 0).single().session.unknown)
    }

    @Test fun `stale screening result is not attached to a later call`() {
        val t = CallTracker()
        t.onScreened("5559998888", unknown = true, at = 0)
        val started = t.onCallState(PhoneCallState.RINGING, 60_000).single()
        assertFalse(started.session.unknown)
    }

    @Test fun `rejected screening result never becomes a session`() {
        val t = CallTracker()
        t.onScreened("5559998888", unknown = true, at = 0, rejected = true)
        val started = t.onCallState(PhoneCallState.RINGING, 500).single()
        assertFalse(started.session.unknown)
    }

    @Test fun `gm_pause_005 missed unknown call ends unanswered`() {
        val t = CallTracker()
        t.onScreened("5559998888", unknown = true, at = 0)
        t.onCallState(PhoneCallState.RINGING, 100)
        val ended = t.onCallState(PhoneCallState.IDLE, 20_000).single()
        assertFalse(ended.session.answered)
    }

    @Test fun `gm_pause_011 gm_scrn_010 outgoing call is known and answered immediately`() {
        val t = CallTracker()
        val updates = t.onCallState(PhoneCallState.OFFHOOK, 0)
        assertEquals(2, updates.size)
        assertFalse(updates[0].session.incoming)
        assertFalse(updates[0].session.unknown)
    }

    @Test fun `gm_pause_012 call waiting does not replace the active call`() {
        val t = CallTracker()
        t.onScreened("5559998888", unknown = true, at = 0)
        t.onCallState(PhoneCallState.RINGING, 100)
        t.onCallState(PhoneCallState.OFFHOOK, 1_000)
        assertTrue(t.onCallState(PhoneCallState.RINGING, 2_000).isEmpty())
        assertTrue(t.activeCall!!.unknown)
    }
}
