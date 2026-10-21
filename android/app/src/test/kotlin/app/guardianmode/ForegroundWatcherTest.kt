package app.guardianmode

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.guardianmode.protection.ForegroundWatcher
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

/**
 * qa/antigravity/cases/06-pause-cards.md GM-PAUSE-047: Guardian sees an app come to the front
 * even when Android records the switch a moment late.
 *
 * Android stamps an app switch with the time it happened, then stores it on its usage-stats
 * handler thread, so on a busy phone a query can run after that time but before the event is
 * stored. ForegroundWatcher.poll (AppWatchers.kt) asks for [last poll, now) and moves the start
 * to `now`, so an event stored late, stamped before the last poll, is never asked for again:
 * known bug FOREGROUND-WINDOW. On the API 33 run of 64f203e (gm_call_003) Usage access listed
 * Chase in front 8 s before the failure while the brain still had Guardian in front, polling
 * every second during the call, and no pause card came.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ForegroundWatcherTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val usage = shadowOf(context.getSystemService(UsageStatsManager::class.java))

    @Test fun gm_pause_047_appSwitchStoredInTimeIsSeen() {
        val watcher = ForegroundWatcher(context)
        val t0 = System.currentTimeMillis()
        assertThat(watcher.poll(t0)).isNull()
        usage.addEvent(CHASE, t0 + 300, UsageEvents.Event.ACTIVITY_RESUMED)
        assertThat(watcher.poll(t0 + 1_000)?.packageName).isEqualTo(CHASE)
    }

    @Category(KnownBug::class)
    @Test fun gm_pause_047_appSwitchStoredLateIsStillSeen() {
        val watcher = ForegroundWatcher(context)
        val t0 = System.currentTimeMillis()
        assertThat(watcher.poll(t0)).isNull()
        // Chase came to the front 200 ms before that poll, but Android stored the event after it.
        usage.addEvent(CHASE, t0 - 200, UsageEvents.Event.ACTIVITY_RESUMED)
        assertWithMessage("the next poll reports Chase").that(watcher.poll(t0 + 1_000)?.packageName).isEqualTo(CHASE)
    }

    private companion object {
        const val CHASE = "com.chase.sig.android"
    }
}
