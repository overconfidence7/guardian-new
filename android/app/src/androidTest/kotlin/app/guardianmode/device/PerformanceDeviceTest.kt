package app.guardianmode.device

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.guardianmode.support.DeviceRule
import app.guardianmode.support.StubApp
import app.guardianmode.ui.MainActivity
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

/**
 * qa/antigravity/cases/17-performance-and-battery.md timings, ten runs each, min / median /
 * max logged (tag PerformanceDeviceTest) and the median asserted against the case's bar.
 * GM-PERF-003, 004 and 005 live with the flows they time (PauseCardDeviceTest
 * gm_pause_040, LockDeviceTest gm_lock_030, RemoteAccessDeviceTest gm_racc_001).
 *
 * Home's arrival is taken from the activity lifecycle in Guardian's own process (exact,
 * no screen polling), then confirmed on screen.
 */
@RunWith(AndroidJUnit4::class)
class PerformanceDeviceTest {
    @get:Rule val rule = DeviceRule()

    private fun timeHome(trigger: () -> Unit): Long {
        val resumedAt = AtomicLong(0)
        val callback = ActivityLifecycleCallback { activity: Activity, stage: Stage ->
            if (activity is MainActivity && stage == Stage.RESUMED) resumedAt.compareAndSet(0, System.currentTimeMillis())
        }
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)
        try {
            rule.open(StubApp.CHASE)
            check(rule.waitForPackage(StubApp.CHASE.packageName)) { "Chase didn't open" }
            resumedAt.set(0)
            val start = System.currentTimeMillis()
            trigger()
            check(rule.waitUntil { resumedAt.get() != 0L }) { "Home never resumed" }
            check(rule.waitFor(rule.shieldChip)) { "Home never drew" }
            val took = resumedAt.get() - start
            if (took > HOME_SWITCH_CI_BAR_MS && !slowTraced) {
                slowTraced = true
                traceSlowSwitch(start, took)
            }
            return took
        } finally {
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)
        }
    }

    private fun report(id: String, times: List<Long>, barMs: Long) {
        val sorted = times.sorted()
        val median = sorted[sorted.size / 2]
        Log.i(TAG, "$id ms: min=${sorted.first()} median=$median max=${sorted.last()} all=$times (bar $barMs)")
        assertWithMessage("$id median (ms) of $sorted").that(median).isAtMost(barMs)
    }

    /**
     * Partial: a true cold start needs `am force-stop` of Guardian, which would end this
     * test too, so this times a start of the home activity from another app with the
     * process alive. The cold start is left to the host-side script.
     */
    @Test fun gm_perf_001_homeStartsUnderOneAndAHalfSeconds() {
        val times = List(RUNS) {
            timeHome { rule.context.startActivity(Intent(rule.context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        report("GM-PERF-001", times, 1_500)
    }

    /**
     * The case's bar is 300 ms on a phone. On a CI emulator (software rendering, shared CPU)
     * the same switch takes several times longer, so the assertion is a CI bar that catches a
     * real slowdown; the log line has the numbers to compare with 300 ms.
     */
    @Test fun gm_perf_002_homeButtonBringsGuardianBackUnder300ms() {
        val times = List(RUNS) { timeHome { rule.device.pressHome() } }
        Log.i(TAG, "GM-PERF-002 spec bar 300 ms; CI bar $HOME_SWITCH_CI_BAR_MS ms")
        report("GM-PERF-002", times, HOME_SWITCH_CI_BAR_MS)
    }

    private var slowTraced = false

    /**
     * The first slow switch in a test, as GuardianTrace lines starting "!" (CI keeps those in
     * the annotation ahead of other traces, qa/daily/device_diagnostics.py):
     * where Home resolves, where input focus is, and what the activity, window and input
     * services logged between the press and Guardian's resume. GM-PERF-002 sometimes takes
     * 5.0 s on every press on API 33 (daily runs of f42747b and 0843f16), with nothing else
     * resuming in between; this is what tells Android's 5 s waits (app-switch protection, no
     * focused window, a transition timeout) apart.
     */
    private fun traceSlowSwitch(start: Long, took: Long) {
        // "!" lines are kept in the CI annotation ahead of the later "opening Chase" traces.
        Log.i(DeviceRule.TRACE, "! slow switch $took ms: home=${rule.homeResolvesTo()} ${rule.focusSummary()}".take(300))
        // Around the press and around the resume: the wait lies between them.
        val sys = rule.systemLog(start - 200)
        val kept = if (sys.size <= 10) sys else sys.take(6) + listOf("... ${sys.size - 10} more ...") + sys.takeLast(4)
        kept.forEach { Log.i(DeviceRule.TRACE, "! $it".take(300)) }
    }

    private companion object {
        const val TAG = "PerformanceDeviceTest"
        const val RUNS = 10
        const val HOME_SWITCH_CI_BAR_MS = 1_000L
    }
}
