package app.guardianmode.ui

import androidx.compose.ui.platform.LocalConfiguration
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.guardianmode.GuardianApp
import app.guardianmode.core.LockedAction
import app.guardianmode.core.ReminderSchedule
import app.guardianmode.core.TileKind
import app.guardianmode.core.Transparency
import app.guardianmode.data.HelperInfo
import app.guardianmode.protection.GuardianService
import app.guardianmode.ui.help.HelpActivity
import app.guardianmode.ui.home.AllAppsScreen
import app.guardianmode.ui.home.AppEntry
import app.guardianmode.ui.home.HomeActions
import app.guardianmode.ui.home.HomeModel
import app.guardianmode.ui.home.HomeScreen
import app.guardianmode.ui.home.PhotosScreen
import app.guardianmode.ui.home.RemindersScreen
import app.guardianmode.ui.home.TileUi
import app.guardianmode.ui.lock.LockSettingsScreen
import app.guardianmode.ui.lock.UnlockScreen
import app.guardianmode.ui.onboarding.OnboardingActivity
import app.guardianmode.ui.theme.GuardianTheme
import app.guardianmode.ui.transparency.TransparencyActions
import app.guardianmode.ui.transparency.TransparencyScreen
import app.guardianmode.ui.transparency.TransparencyUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** The home screen. Guardian is the phone's launcher, so this activity is always one tap away. */
class MainActivity : ComponentActivity() {

    private val container get() = (application as GuardianApp).container
    private var destination by mutableStateOf(DEST_HOME)
    private var needsAttention by mutableStateOf(false)
    /** Where to go back to, and what to do, once the lock code is entered. */
    private var unlockReturn = DEST_HOME
    private var afterUnlock: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        destination = intent.getStringExtra(EXTRA_DESTINATION) ?: DEST_HOME
        setContent { Content() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing the Home button while already home always lands on the main screen.
        destination = intent.getStringExtra(EXTRA_DESTINATION) ?: DEST_HOME
    }

    override fun onResume() {
        super.onResume()
        val state = container.store.current
        if (!state.onboardingDone) {
            // Checked on every resume, not just at creation: this activity is the launcher and outlives setup,
            // and the home screen must never show as if setup had finished.
            startActivity(Intent(this, OnboardingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            return
        }
        GuardianService.start(this)
        needsAttention = container.protection.needsAttention()
        lifecycleScope.launch {
            container.protection.onUserActive()
            container.cloud.flushOutbox()
        }
    }

    @Composable
    private fun Content() {
        val state by container.store.state.collectAsStateWithLifecycle()
        val requests by container.cloud.pendingRequests.collectAsStateWithLifecycle()
        var now by remember { mutableStateOf(ZonedDateTime.now()) }
        LaunchedEffect(Unit) {
            while (true) {
                now = ZonedDateTime.now()
                delay(10_000)
            }
        }
        GuardianTheme(state.config.display) {
            BackHandler(enabled = destination != DEST_HOME) { destination = if (destination == DEST_UNLOCK) unlockReturn else DEST_HOME }
            when (destination) {
                DEST_APPS -> AllAppsScreen(
                    apps = state.config.allowedApps.mapNotNull { pkg -> appLabel(pkg)?.let { AppEntry(pkg, it) } }.sortedBy { it.label.lowercase() },
                    onOpen = { Launch.app(this, it.packageName) },
                    onPhoneSettings = { withCode(LockedAction.PHONE_SETTINGS) { Launch.phoneSettings(this) } },
                    onBack = { destination = DEST_HOME },
                )
                DEST_PHOTOS -> PhotosScreen(
                    photos = state.photos + state.localPhotos,
                    now = now,
                    onBack = { destination = DEST_HOME },
                    onAdd = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onRemove = { photo -> lifecycleScope.launch { container.photos.remove(photo.id) } },
                )
                DEST_UNLOCK -> UnlockScreen(
                    tryUnlock = container.lock::tryUnlock,
                    onUnlocked = {
                        destination = unlockReturn
                        afterUnlock?.invoke()
                        afterUnlock = null
                    },
                    onBack = { destination = unlockReturn; afterUnlock = null },
                )
                DEST_LOCK -> LockSettingsScreen(
                    level = state.config.lock.level,
                    hasCode = state.lockCodeHash.isNotEmpty(),
                    onLevel = { level -> lifecycleScope.launch { container.protection.setLockLevel(level) } },
                    onSetCode = { code -> lifecycleScope.launch { container.lock.setCode(code) } },
                    onLockNow = {
                        lifecycleScope.launch { container.lock.relock() }
                        destination = DEST_HOME
                    },
                    onBack = { destination = DEST_TRANSPARENCY },
                )
                DEST_REMINDERS -> RemindersScreen(
                    // Same zone the alarms are set in, so each reminder's "Done" key matches the one recorded.
                    today = ReminderSchedule.zoneOf(state.config.checkIn.timezone).let { zone ->
                        ReminderSchedule.upcomingToday(state.config.reminders, now.withZoneSameInstant(zone).toLocalDate().atStartOfDay(zone))
                    },
                    done = state.remindersDone.keys,
                ) { destination = DEST_HOME }
                DEST_TRANSPARENCY -> TransparencyScreen(
                    ui = TransparencyUi(
                        items = Transparency.items(state.config),
                        helpers = state.helpers,
                        shared = state.sharedEvents,
                        changes = state.changes,
                        paused = state.isPaused(System.currentTimeMillis()),
                        pausedUntil = state.pausedUntil.takeIf { it > 0 }?.let {
                            Instant.ofEpochMilli(it).atZone(now.zone).format(DateTimeFormatter.ofPattern("h:mm a", LocalConfiguration.current.locales[0]))
                        },
                        paired = state.pairing != null,
                        lockLevel = state.config.lock.level,
                        hasCode = state.lockCodeHash.isNotEmpty(),
                    ),
                    actions = transparencyActions,
                )
                else -> HomeScreen(
                    ui = HomeModel.build(state, now, ::appLabel, needsAttention, requests),
                    now = now,
                    actions = homeActions,
                )
            }
        }
    }

    /** Android's own photo picker: no permission needed, and it includes Google Photos where the phone offers it. */
    private val pickPhotos = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        if (uris.isNotEmpty()) lifecycleScope.launch { container.photos.import(uris) }
    }

    /** Runs [block] straight away, or after the lock code when [action] needs it. */
    private fun withCode(action: LockedAction, block: () -> Unit) {
        if (!container.lock.needsCode(action)) {
            block()
            return
        }
        unlockReturn = destination
        afterUnlock = block
        destination = DEST_UNLOCK
    }

    private fun appLabel(pkg: String): String? = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    private val homeActions = object : HomeActions {
        override fun onTile(tile: TileUi) {
            when (tile.kind) {
                TileKind.CALL_CONTACT -> Launch.call(this@MainActivity, tile.phone)
                TileKind.PHONE -> Launch.dialer(this@MainActivity)
                TileKind.MESSAGES -> Launch.messages(this@MainActivity)
                TileKind.CAMERA -> Launch.camera(this@MainActivity)
                TileKind.APP -> Launch.app(this@MainActivity, tile.target)
                TileKind.PHOTOS -> destination = DEST_PHOTOS
                TileKind.REMINDERS -> destination = DEST_REMINDERS
                TileKind.ALL_APPS -> onAllApps()
                TileKind.HELP -> startActivity(Intent(this@MainActivity, HelpActivity::class.java))
            }
        }

        override fun onPhoto() { destination = DEST_PHOTOS }
        override fun onShield() { destination = DEST_TRANSPARENCY }
        override fun onAllApps() = withCode(LockedAction.ALL_APPS) { destination = DEST_APPS }
        override fun onReminders() { destination = DEST_REMINDERS }
        override fun onHelperDecision(uid: String, approve: Boolean) {
            lifecycleScope.launch { runCatching { container.cloud.approveHelper(uid, approve) } }
        }
        override fun onRequestDecision(id: String, approve: Boolean) {
            val request = container.cloud.pendingRequests.value.firstOrNull { it.id == id } ?: return
            lifecycleScope.launch { runCatching { container.cloud.answerRequest(request, approve) } }
        }
    }

    private val transparencyActions = object : TransparencyActions {
        override fun onToggle(key: String, enabled: Boolean) = withCode(LockedAction.PROTECTION_SETTINGS) {
            lifecycleScope.launch { container.protection.setFeature(key, enabled) }
        }

        override fun onPauseHour() = withCode(LockedAction.PROTECTION_SETTINGS) {
            lifecycleScope.launch { container.protection.pauseFor(60 * 60_000L) }
        }

        override fun onResume() {
            lifecycleScope.launch { container.protection.resume() }
        }

        override fun onRemoveHelper(helper: HelperInfo) = withCode(LockedAction.PROTECTION_SETTINGS) {
            lifecycleScope.launch { runCatching { container.cloud.removeHelper(helper.uid) } }
        }

        override fun onLockSettings() = withCode(LockedAction.LOCK_SETTINGS) { destination = DEST_LOCK }

        override fun onStopUsing() = withCode(LockedAction.STOP_USING) {
            lifecycleScope.launch {
                container.cloud.leaveFamily()
                container.store.update {
                    it.copy(onboardingDone = false, consent = emptyMap(), lockCodeHash = "", lockCodeSalt = "", unlockedUntil = 0, lockFailures = 0, lockRetryAt = 0)
                }
                GuardianService.stop(this@MainActivity)
                OnboardingActivity.clearProgress(this@MainActivity)
                Launch.homeSettings(this@MainActivity)
            }
        }

        override fun onBack() { destination = DEST_HOME }
    }

    companion object {
        const val EXTRA_DESTINATION = "destination"
        const val DEST_HOME = "home"
        const val DEST_APPS = "apps"
        const val DEST_PHOTOS = "photos"
        const val DEST_REMINDERS = "reminders"
        const val DEST_TRANSPARENCY = "transparency"
        const val DEST_UNLOCK = "unlock"
        private const val MAX_PICK = 10
        const val DEST_LOCK = "lock"
    }
}
