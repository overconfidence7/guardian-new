package app.guardianmode.protection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.guardianmode.GuardianApp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps protection running, with the persistent notification Play requires. Watches
 * which app is in front only while the screen is on, and polls quickly only while a
 * call is live or the tripwires are armed, so battery cost stays negligible.
 */
class GuardianService : LifecycleService() {

    private val container get() = (application as GuardianApp).container
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private lateinit var foreground: ForegroundWatcher

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> lifecycleScope.launch {
                    container.protection.onUserActive()
                    container.protection.checkInstalls()
                }
                Intent.ACTION_PACKAGE_ADDED -> lifecycleScope.launch { container.protection.checkInstalls() }
            }
            wake.trySend(Unit)
        }
    }

    override fun onCreate() {
        super.onCreate()
        foreground = ForegroundWatcher(this)
        val paused = container.store.current.isPaused(System.currentTimeMillis())
        ServiceCompat.startForeground(
            this,
            Notifications.ID_PROTECTION,
            Notifications.protection(this, paused),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        ContextCompat.registerReceiver(
            this, receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this, receiver,
            IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        lifecycleScope.launch { watchLoop() }
        lifecycleScope.launch {
            container.store.state.map { it.isPaused(System.currentTimeMillis()) }.distinctUntilChanged().collect { p ->
                ServiceCompat.startForeground(
                    this@GuardianService, Notifications.ID_PROTECTION,
                    Notifications.protection(this@GuardianService, p),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            }
        }
        lifecycleScope.launch {
            container.protection.checkInstalls()
            while (isActive) {
                container.protection.reportStatus()
                delay(STATUS_INTERVAL)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private suspend fun watchLoop() {
        val power = getSystemService(PowerManager::class.java)
        val pm = packageManager
        while (lifecycleScope.isActive) {
            val brain = container.brain
            val screenOn = power.isInteractive
            if (screenOn && container.capabilities.has(Capability.USAGE_ACCESS)) {
                foreground.poll()?.let { pkg ->
                    val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                    brain.onForeground(pkg, label)
                }
            }
            if (brain.callActive) brain.onTick()
            val pause = when {
                !screenOn -> if (brain.callActive) 5_000L else 30_000L
                brain.callActive || brain.isArmed() || brain.lockedLevel -> 1_000L
                else -> 3_000L
            }
            withTimeoutOrNull(pause) { wake.receive() }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GuardianService"
        private const val STATUS_INTERVAL = 6 * 60 * 60_000L

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, GuardianService::class.java))
            }.onFailure {
                // Android may refuse a start from the background; the next unlock or home visit retries.
                Log.w(TAG, "Could not start protection service now", it)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuardianService::class.java))
        }
    }
}
