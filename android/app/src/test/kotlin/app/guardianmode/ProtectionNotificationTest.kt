package app.guardianmode

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.guardianmode.cloud.CloudSync
import app.guardianmode.data.LocalState
import app.guardianmode.data.LocalStore
import app.guardianmode.protection.Capabilities
import app.guardianmode.protection.CardLauncher
import app.guardianmode.protection.GuardianBrain
import app.guardianmode.protection.InstallWatcher
import app.guardianmode.protection.LockManager
import app.guardianmode.protection.Notifications
import app.guardianmode.protection.ProtectionController
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * qa/antigravity/cases/11-service-and-notifications.md GM-SVC-014: the persistent notification
 * shows the state the parent just chose.
 *
 * Was known bug NOTIF-STALE: ProtectionController.pauseFor/resume write the state, then post the
 * notification from LocalStore.current. `current` is a StateFlow that a collector on the IO
 * dispatcher refreshes after each write (LocalStore.kt `stateIn`), so right after the write it
 * can still hold the old state, and the controller posts the opposite of what was chosen. The
 * service posts the right one when its own collector catches up, but the two posts race, and
 * when the controller's lands last the shade keeps saying "paused" after "Turn back on" (PR #10's
 * API 36 run: shade "Guardian Mode is paused", saved state not paused, 25 s later still so).
 *
 * Here the collector never runs, the slowest case of that race, so it fails every time.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ProtectionNotificationTest {

    /** A DataStore in memory: writes land at once, readers of LocalStore see them only through its collector. */
    private class MemoryDataStore(initial: LocalState) : DataStore<LocalState> {
        private val flow = MutableStateFlow(initial)
        override val data: Flow<LocalState> = flow
        override suspend fun updateData(transform: suspend (t: LocalState) -> LocalState): LocalState =
            transform(flow.value).also { flow.value = it }
    }

    private fun title(context: Context): String? =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.id == Notifications.ID_PROTECTION }
            ?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    @Test fun gm_svc_014_notificationShowsTheStateJustChosen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Notifications.createChannels(context)
        // The scope LocalStore refreshes `current` on: its tasks are never run.
        val stalled = CoroutineScope(Executor { }.asCoroutineDispatcher())
        val store = LocalStore(MemoryDataStore(LocalState(onboardingDone = true)), stalled)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val capabilities = Capabilities(context)
        val lock = LockManager(context, store)
        val brain = GuardianBrain(store, CardLauncher(context, capabilities), capabilities, lock, scope)
        val controller = ProtectionController(context, store, CloudSync(context, store, scope), capabilities, InstallWatcher(context), brain)

        controller.pauseFor(60 * 60_000L)
        assertWithMessage("notification right after \"Pause for 1 hour\"").that(title(context)).isEqualTo("Guardian Mode is paused")

        controller.resume()
        assertWithMessage("notification right after \"Turn back on\"").that(title(context)).isEqualTo("Guardian Mode is on")
    }
}
