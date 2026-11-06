package app.guardianmode

import android.app.Application
import app.guardianmode.protection.GuardianService
import app.guardianmode.protection.Notifications
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class GuardianApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        // Firebase comes up only on a paired phone (start() sets it up first); an unpaired
        // phone runs the welcome and consent screens without it (GM-PERF-007).
        container.cloud.start()
        container.reminders.reschedule()
        // Reminders follow config edits from helpers.
        container.appScope.launch {
            container.store.state.map { it.config.reminders to it.config.checkIn.timezone }
                .distinctUntilChanged()
                .drop(1)
                .collect { container.reminders.reschedule() }
        }
        if (container.store.current.onboardingDone) GuardianService.start(this)
    }
}
