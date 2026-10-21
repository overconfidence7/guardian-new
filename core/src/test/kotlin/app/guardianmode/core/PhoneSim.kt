package app.guardianmode.core

/**
 * A test double for the parent's phone at the rules level: it wires CallScreeningPolicy,
 * CallTracker and TripwireEngine together the way the Android glue (GuardianBrain,
 * GuardianCallScreeningService) does, with an injected clock instead of the wall clock.
 *
 * Android only asks the screening app about an incoming call when Guardian holds the
 * call-screening role, the number is visible, and either Guardian can read contacts or
 * the number isn't one of the phone's contacts. [ring] follows those rules so the
 * screening-role / READ_CONTACTS combinations (GM-PAUSE-009, suspect S-13) can be played.
 */
class PhoneSim(
    var config: DeviceConfig = DeviceConfig(),
    private val contactsGranted: Boolean = true,
    private val screeningRoleHeld: Boolean = true,
    /** Numbers saved in the phone's own Contacts app. */
    private val phoneContacts: Set<String> = emptySet(),
    start: Long = 1_700_000_000_000L,
) {
    var now: Long = start
        private set

    private var ids = 0
    val screening = CallScreeningPolicy()

    // GuardianBrain wires the tracker with screenerSeesContacts = READ_CONTACTS granted.
    val tracker = CallTracker(screenerSeesContacts = { contactsGranted })
    val engine = TripwireEngine({ config }, newId = { "e${ids++}" })

    /** Everything the rules asked the phone to do, in order. */
    val actions = mutableListOf<GuardianAction>()
    val events: List<GuardianEvent> get() = actions.filterIsInstance<GuardianAction.Share>().map { it.event }

    /** The verdict of the most recent screening, or null when Android didn't ask Guardian. */
    var lastDecision: ScreeningDecision? = null
        private set

    fun advanceMillis(millis: Long) {
        now += millis
    }

    fun advanceMinutes(minutes: Long) = advanceMillis(minutes * MINUTE)

    /** An incoming call starts ringing. Returns the actions it caused. */
    fun ring(number: String?, verificationFailed: Boolean = false): List<GuardianAction> {
        val inPhoneContacts = number != null && phoneContacts.any { PhoneNumbers.sameLine(it, number) }
        val androidAsksGuardian = screeningRoleHeld && !number.isNullOrBlank() && (contactsGranted || !inPhoneContacts)
        lastDecision = null
        if (androidAsksGuardian) {
            val d = screening.decide(number, now, config, verificationFailed, inPhoneContacts && contactsGranted)
            lastDecision = d
            tracker.onScreened(number, d.unknown, now, rejected = d.verdict == ScreeningVerdict.SEND_TO_VOICEMAIL)
            // A call sent to voicemail never rings on the phone.
            if (d.verdict == ScreeningVerdict.SEND_TO_VOICEMAIL) return emptyList()
        }
        advanceMillis(500)
        return state(PhoneCallState.RINGING)
    }

    fun answer(): List<GuardianAction> {
        advanceMillis(2_000)
        return state(PhoneCallState.OFFHOOK)
    }

    fun hangUp(): List<GuardianAction> = state(PhoneCallState.IDLE)

    /** The parent places a call (OFFHOOK straight from IDLE). */
    fun dialOut(): List<GuardianAction> = state(PhoneCallState.OFFHOOK)

    /**
     * Time passes during a call. With [ticks] the service's periodic tick runs every
     * [tickMillis] like GuardianService does; without, nothing checks until hang-up.
     */
    fun talk(millis: Long, ticks: Boolean = true, tickMillis: Long = 5_000): List<GuardianAction> {
        val out = mutableListOf<GuardianAction>()
        if (!ticks) {
            advanceMillis(millis)
            return out
        }
        var left = millis
        while (left > 0) {
            val step = minOf(tickMillis, left)
            advanceMillis(step)
            left -= step
            out += record(engine.onTick(now))
        }
        return out
    }

    fun talkMinutes(minutes: Long, ticks: Boolean = true) = talk(minutes * MINUTE, ticks)

    /** The test cases' "standard unknown call": ring, answer, talk 40 s, hang up. */
    fun standardCall(number: String = UNKNOWN): List<GuardianAction> =
        ring(number) + answer() + talk(40_000) + hangUp()

    fun open(packageName: String, label: String): List<GuardianAction> = record(engine.onForeground(packageName, label, now))

    /** Back to Guardian's home screen (Guardian is the launcher). */
    fun goHome(): List<GuardianAction> = open(GUARDIAN, "Guardian Mode")

    fun install(packageName: String, label: String, controlCapable: Boolean = false): List<GuardianAction> =
        record(engine.onPackageInstalled(packageName, label, controlCapable, now))

    fun pauseOutcome(packageName: String, label: String, outcome: PauseOutcome): List<GuardianAction> =
        record(engine.onPauseOutcome(packageName, label, outcome, now))

    private fun state(s: PhoneCallState): List<GuardianAction> =
        record(tracker.onCallState(s, now).flatMap { engine.onCall(it, now) })

    private fun record(list: List<GuardianAction>): List<GuardianAction> {
        actions += list
        return list
    }

    companion object {
        const val MINUTE = TripwireEngine.MINUTE
        const val GUARDIAN = "app.guardianmode"
        const val UNKNOWN = "5559998888"
        const val SARAH = "5550101111"
        const val CHASE = "com.chase.sig.android"
        const val VENMO = "com.venmo"
        const val ZELLE = "com.zellepay.zelle"
        const val TARGET = "com.target.ui"
        const val COINBASE = "com.coinbase.android"
        const val ANYDESK = "com.anydesk.anydeskandroid"
        const val TEAMVIEWER = "com.teamviewer.quicksupport.market"
        const val NOTES = "com.example.notes"

        /** The family state the case files inject (templates/state-family.json, in short). */
        fun familyConfig(): DeviceConfig = DeviceConfig(
            parentName = "Margaret",
            family = listOf(
                FamilyContact("sarah", "Sarah", "Daughter", phone = SARAH, isPrimaryHelper = true),
                FamilyContact("david", "David", "Son", phone = "5550102222"),
            ),
            contacts = listOf(Contact("patel", "Dr. Patel", "5550103333")),
            protection = ProtectionSettings(
                callScreening = ScreeningSettings(allowlist = listOf(AllowedNumber("Pharmacy", "+1 555 010 4444"))),
                safeWord = SafeWordSettings(word = "bluebird"),
            ),
        )
    }
}

fun List<GuardianAction>.pauseCards() = filterIsInstance<GuardianAction.ShowPauseCard>()
fun List<GuardianAction>.remoteWarnings() = filterIsInstance<GuardianAction.ShowRemoteAccessWarning>()
fun List<GuardianAction>.shared() = filterIsInstance<GuardianAction.Share>().map { it.event }
fun List<GuardianAction>.hasSafeWordCard() = any { it == GuardianAction.ShowSafeWordCard }
