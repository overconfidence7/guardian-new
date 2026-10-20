package app.guardianmode.ui.cards

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.guardianmode.GuardianApp
import app.guardianmode.core.GuardianJson
import app.guardianmode.core.PauseOutcome
import app.guardianmode.protection.UnlockResult
import app.guardianmode.ui.Launch
import app.guardianmode.ui.MainActivity
import app.guardianmode.ui.theme.GuardianTheme
import kotlinx.coroutines.launch

/** Hosts every full-screen card: pause, remote-access warning, safe word and reminders. */
class GuardianCardActivity : ComponentActivity() {

    private val container get() = (application as GuardianApp).container
    private var request by mutableStateOf<CardRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        request = read(intent)
        if (request == null) {
            finish()
            return
        }
        // Back on a locked app's cover goes home, never back into the app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val req = request
                if (req?.kind == CardKind.LOCKED) actions(req).goHome() else finish()
            }
        })
        setContent {
            val state = container.store.current
            GuardianTheme(state.config.display) {
                request?.let { req ->
                    CardContent(
                        request = req,
                        config = state.config,
                        actions = actions(req),
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)?.let { request = it }
    }

    private fun read(intent: Intent): CardRequest? = intent.getStringExtra(EXTRA_REQUEST)?.let {
        runCatching { GuardianJson.json.decodeFromString(CardRequest.serializer(), it) }.getOrNull()
    }

    private fun actions(req: CardRequest) = object : CardActions {
        override fun callFamily(phone: String) {
            if (req.kind == CardKind.PAUSE) container.brain.onPauseOutcome(req.packageName, req.appLabel, PauseOutcome.CALLED_FAMILY)
            Launch.call(this@GuardianCardActivity, phone)
            finish()
        }

        override fun continueAnyway() {
            if (req.kind == CardKind.PAUSE) container.brain.onPauseOutcome(req.packageName, req.appLabel, PauseOutcome.CONTINUED)
            finish()
        }

        override fun goHome() {
            if (req.kind == CardKind.PAUSE) container.brain.onPauseOutcome(req.packageName, req.appLabel, PauseOutcome.CLOSED)
            startActivity(Intent(this@GuardianCardActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }

        override fun removeApp() {
            Launch.uninstall(this@GuardianCardActivity, req.packageName)
            finish()
        }

        override fun reminderDone() {
            lifecycleScope.launch {
                container.reminders.markDone(req)
                finish()
            }
        }

        override fun snooze() {
            container.reminders.snooze(req)
            finish()
        }

        override fun dismiss() = finish()

        override suspend fun tryUnlock(code: String): UnlockResult = container.lock.tryUnlock(code)
    }


    companion object {
        private const val EXTRA_REQUEST = "request"

        fun intent(context: Context, request: CardRequest): Intent =
            Intent(context, GuardianCardActivity::class.java)
                .putExtra(EXTRA_REQUEST, GuardianJson.json.encodeToString(CardRequest.serializer(), request))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
