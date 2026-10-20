package app.guardianmode.protection

import android.util.Log
import app.guardianmode.core.AppCatalog
import app.guardianmode.core.CallScreeningPolicy
import app.guardianmode.core.CallTracker
import app.guardianmode.core.GuardianAction
import app.guardianmode.core.PauseOutcome
import app.guardianmode.core.PhoneCallState
import app.guardianmode.core.ScreeningDecision
import app.guardianmode.core.TripwireEngine
import app.guardianmode.data.LocalStore
import app.guardianmode.ui.cards.CardKind
import app.guardianmode.ui.cards.CardRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Glue between Android signals and the pure rules in :core. All rule state is touched
 * from one single-threaded dispatcher, so the screening service, the phone-state
 * receiver and the protection service can call in from any thread.
 */
class GuardianBrain(
    private val store: LocalStore,
    private val cards: CardLauncher,
    private val capabilities: Capabilities,
    private val lock: LockManager,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val serial = Dispatchers.Default.limitedParallelism(1)

    private val catalog = AppCatalog()
    private val screening = CallScreeningPolicy()
    private val tracker = CallTracker(screenerSeesContacts = { capabilities.has(Capability.CONTACTS) })
    private val engine = TripwireEngine(
        config = { store.current.config },
        catalog = catalog,
        newId = { UUID.randomUUID().toString() },
    )

    /** True while a call is in progress; the service ticks faster and watches apps more closely. */
    @Volatile var callActive: Boolean = false
        private set

    /** Synchronous because CallScreeningService must answer within 5 seconds on its own thread. */
    @Synchronized
    fun screen(number: String?, verificationFailed: Boolean, inPhoneContacts: Boolean): ScreeningDecision {
        val now = clock()
        val decision = screening.decide(number, now, store.current.config, verificationFailed, inPhoneContacts)
        val rejected = decision.verdict == app.guardianmode.core.ScreeningVerdict.SEND_TO_VOICEMAIL
        scope.launch(serial) { tracker.onScreened(number, decision.unknown, now, rejected) }
        return decision
    }

    fun onCallState(state: PhoneCallState) = evaluate { now ->
        callActive = state != PhoneCallState.IDLE
        tracker.onCallState(state, now).flatMap { engine.onCall(it, now) }
    }

    fun onForeground(packageName: String, label: String) {
        // At the Locked level, an app the family didn't pick gets a code screen instead.
        if (lock.blocks(packageName)) {
            cards.show(CardRequest(kind = CardKind.LOCKED, packageName = packageName, appLabel = label))
            return
        }
        evaluate { now -> engine.onForeground(packageName, label, now) }
    }

    /** Polled faster at the Locked level so a locked app is covered quickly. */
    val lockedLevel: Boolean get() = lock.hasCode && lock.level == app.guardianmode.core.LockLevel.LOCKED

    fun onInstalled(packageName: String, label: String, controlCapable: Boolean) =
        evaluate { now -> engine.onPackageInstalled(packageName, label, controlCapable, now) }

    fun onTick() = evaluate { now -> engine.onTick(now) }

    fun onPauseOutcome(packageName: String, label: String, outcome: PauseOutcome) =
        evaluate { now -> engine.onPauseOutcome(packageName, label, outcome, now) }

    fun isArmed(): Boolean = engine.isArmed(clock())

    private fun evaluate(block: (Long) -> List<GuardianAction>) {
        scope.launch(serial) {
            val now = clock()
            val actions = runCatching { block(now) }.getOrElse {
                Log.e(TAG, "Rule evaluation failed", it)
                emptyList()
            }
            // "Pause everything" means no cards and nothing shared, not just quieter cards.
            if (actions.isEmpty() || store.current.isPaused(now)) return@launch
            for (action in actions) {
                when (action) {
                    is GuardianAction.Share -> store.recordShared(action.event)
                    else -> cards.show(action)
                }
            }
        }
    }

    private companion object {
        const val TAG = "GuardianBrain"
    }
}
