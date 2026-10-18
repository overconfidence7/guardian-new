package app.guardianmode.cloud

import app.guardianmode.GuardianApp
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch

/** FCM is only a wake-up: the data itself always comes from Firestore. */
class GuardianMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val container = (application as GuardianApp).container
        container.cloud.start()
        container.appScope.launch { container.cloud.flushOutbox() }
    }

    override fun onNewToken(token: String) {
        val container = (application as GuardianApp).container
        container.appScope.launch { container.cloud.registerPushToken(token) }
    }
}
