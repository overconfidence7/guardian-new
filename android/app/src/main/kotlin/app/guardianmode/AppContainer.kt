package app.guardianmode

import android.content.Context
import app.guardianmode.cloud.CloudSync
import app.guardianmode.data.LocalStore
import app.guardianmode.data.PhotoImporter
import app.guardianmode.protection.Capabilities
import app.guardianmode.protection.CardLauncher
import app.guardianmode.protection.GuardianBrain
import app.guardianmode.protection.InstallWatcher
import app.guardianmode.protection.LockManager
import app.guardianmode.protection.ProtectionController
import app.guardianmode.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-wired dependencies. Small enough that a DI framework would cost more than it saves. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = LocalStore(context, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    val capabilities = Capabilities(context)
    val cards = CardLauncher(context, capabilities)
    val cloud = CloudSync(context, store, appScope)
    val installs = InstallWatcher(context)
    val lock = LockManager(context, store)
    val brain = GuardianBrain(store, cards, capabilities, lock, appScope)
    val protection = ProtectionController(context, store, cloud, capabilities, installs, brain)
    val reminders = ReminderScheduler(context, store)
    val photos = PhotoImporter(context, store)
}
