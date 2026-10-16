package app.guardianmode.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.guardianmode.GuardianApp
import app.guardianmode.core.ReminderSchedule
import app.guardianmode.core.TileKind
import app.guardianmode.core.Transparency
import app.guardianmode.data.HelperInfo
import app.guardianmode.protection.Capabilities
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
import java.util.Locale

/** The home screen. Guardian is the phone's launcher, so this activity is always one tap away. */
class MainActivity : ComponentActivity() {

    private val container get() = (application as GuardianApp).container
    private var destination by mutableStateOf(DEST_HOME)
    private var needsAttention by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!container.store.current.onboardingDone) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
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
        if (!state.onboardingDone) return
        GuardianService.start(this)
        val needed = Capabilities.BY_FEATURE.filterKeys { state.consent[it] == true }.values.flatten().distinct()
        needsAttention = container.capabilities.missing(needed).isNotEmpty()
        lifecycleScope.launch {
            container.protection.onUserActive()
            container.cloud.flushOutbox()
        }
    }

    @Composable
    private fun Content() {
        val state by container.store.state.collectAsStateWithLifecycle()
        var now by remember { mutableStateOf(ZonedDateTime.now()) }
        LaunchedEffect(Unit) {
            while (true) {
                now = ZonedDateTime.now()
                delay(10_000)
            }
        }
        GuardianTheme(state.config.display) {
            BackHandler(enabled = destination != DEST_HOME) { destination = DEST_HOME }
            when (destination) {
                DEST_APPS -> AllAppsScreen(
                    apps = state.config.allowedApps.mapNotNull { pkg -> appLabel(pkg)?.let { AppEntry(pkg, it) } }.sortedBy { it.label.lowercase() },
                    onOpen = { Launch.app(this, it.packageName) },
                    onPhoneSettings = { Launch.phoneSettings(this) },
                    onBack = { destination = DEST_HOME },
                )
                DEST_PHOTOS -> PhotosScreen(state.photos, now) { destination = DEST_HOME }
                DEST_REMINDERS -> RemindersScreen(
                    today = ReminderSchedule.upcomingToday(state.config.reminders, now.toLocalDate().atStartOfDay(now.zone)),
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
                            Instant.ofEpochMilli(it).atZone(now.zone).format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
                        },
                        paired = state.pairing != null,
                    ),
                    actions = transparencyActions,
                )
                else -> HomeScreen(
                    ui = HomeModel.build(state, now, ::appLabel, needsAttention),
                    now = now,
                    actions = homeActions,
                )
            }
        }
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
                TileKind.ALL_APPS -> destination = DEST_APPS
                TileKind.HELP -> startActivity(Intent(this@MainActivity, HelpActivity::class.java))
            }
        }

        override fun onPhoto() { destination = DEST_PHOTOS }
        override fun onShield() { destination = DEST_TRANSPARENCY }
        override fun onAllApps() { destination = DEST_APPS }
        override fun onReminders() { destination = DEST_REMINDERS }
        override fun onHelperDecision(uid: String, approve: Boolean) {
            lifecycleScope.launch { runCatching { container.cloud.approveHelper(uid, approve) } }
        }
    }

    private val transparencyActions = object : TransparencyActions {
        override fun onToggle(key: String, enabled: Boolean) {
            lifecycleScope.launch { container.protection.setFeature(key, enabled) }
        }

        override fun onPauseHour() {
            lifecycleScope.launch { container.protection.pauseFor(60 * 60_000L) }
        }

        override fun onResume() {
            lifecycleScope.launch { container.protection.resume() }
        }

        override fun onRemoveHelper(helper: HelperInfo) {
            lifecycleScope.launch { runCatching { container.cloud.removeHelper(helper.uid) } }
        }

        override fun onStopUsing() {
            lifecycleScope.launch {
                container.cloud.leaveFamily()
                container.store.update { it.copy(onboardingDone = false, consent = emptyMap()) }
                GuardianService.stop(this@MainActivity)
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
    }
}
