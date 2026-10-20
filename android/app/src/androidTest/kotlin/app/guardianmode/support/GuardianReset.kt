package app.guardianmode.support

import app.guardianmode.AppContainer
import app.guardianmode.core.PhoneCallState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Forgets what Guardian's rule engine remembers in memory between tests.
 *
 * Instrumented tests run in Guardian's own process, one test after another, so the
 * in-memory call tracker and tripwire engine would otherwise carry state across tests
 * (an unknown call in one test keeps the tripwires armed for 30 minutes, an AnyDesk
 * warning suppresses the next one for 10, a repeat-caller entry lets the next call ring).
 * On a phone this is the same as the process starting fresh, which a test can't do to its
 * own process. Nothing here changes behavior; it only clears memory, on the brain's own
 * single-threaded dispatcher so it never races a rule evaluation.
 *
 * Field names follow GuardianBrain, TripwireEngine, CallTracker and CallScreeningPolicy;
 * if one is renamed, this fails loudly with NoSuchFieldException.
 */
object GuardianReset {

    fun brain(container: AppContainer) {
        val brain = container.brain
        val serial = get(brain, "serial") as CoroutineDispatcher
        runBlocking {
            withContext(serial) {
                val engine = get(brain, "engine")
                set(engine, "lastUnknownCallEndedAt", null)
                set(engine, "activeUnknown", null)
                set(engine, "longCallShared", false)
                set(engine, "riskyAppOpenedDuringCall", false)
                set(engine, "foregroundPackage", null)
                (get(engine, "pauseShownAt") as MutableMap<*, *>).clear()
                (get(engine, "continuedPackages") as MutableSet<*>).clear()
                (get(engine, "remoteWarnedAt") as MutableMap<*, *>).clear()

                val tracker = get(brain, "tracker")
                set(tracker, "pendingScreen", null)
                set(tracker, "current", null)
                set(tracker, "lastState", PhoneCallState.IDLE)

                (get(get(brain, "screening"), "recentUnknown") as MutableCollection<*>).clear()
                set(brain, "callActive", false)
            }
        }
    }

    private fun field(target: Any, name: String) =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun get(target: Any, name: String): Any = field(target, name).get(target)!!

    private fun set(target: Any, name: String, value: Any?) = field(target, name).set(target, value)
}
