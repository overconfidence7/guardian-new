package app.guardianmode.ui.onboarding

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.guardianmode.GuardianApp
import app.guardianmode.cloud.CloudSync
import app.guardianmode.cloud.InvitePreview
import app.guardianmode.protection.Capability
import app.guardianmode.protection.Features
import app.guardianmode.protection.GuardianService
import app.guardianmode.ui.MainActivity
import app.guardianmode.ui.theme.GuardianTheme
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.launch

enum class Stage { WELCOME, CODE, SCAN, CONFIRM, CONSENT, DONE }

/**
 * Setup happens on the parent's phone with the parent present: they enter the code
 * their family member shows them, confirm who will help, and say yes or no to each
 * feature before Android asks for any permission.
 */
class OnboardingActivity : ComponentActivity() {

    private val container get() = (application as GuardianApp).container

    private var stage by mutableStateOf(Stage.WELCOME)
    private var code by mutableStateOf("")
    private var preview by mutableStateOf<InvitePreview?>(null)
    private var busy by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var pageIndex by mutableIntStateOf(0)
    /** Bumped whenever we come back from a permission screen, so the checklist re-reads. */
    private var refresh by mutableIntStateOf(0)
    private var accepted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GuardianTheme {
                BackHandler(enabled = stage != Stage.WELCOME) { back() }
                OnboardingFlow(
                    stage = stage,
                    code = code,
                    preview = preview,
                    busy = busy,
                    error = error,
                    page = ConsentPages.all[pageIndex],
                    pageNumber = pageIndex + 1,
                    pageCount = ConsentPages.all.size,
                    accepted = accepted,
                    refreshKey = refresh,
                    granted = { container.capabilities.has(it) },
                    actions = actions,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh++
    }

    private fun back() {
        error = null
        stage = when (stage) {
            Stage.CODE, Stage.WELCOME -> Stage.WELCOME
            Stage.SCAN, Stage.CONFIRM -> Stage.CODE
            Stage.CONSENT -> if (pageIndex > 0) {
                pageIndex--
                accepted = container.store.current.consent[ConsentPages.all[pageIndex].key] == true
                Stage.CONSENT
            } else if (container.store.current.pairing != null) Stage.CONSENT else Stage.CODE
            Stage.DONE -> Stage.CONSENT
        }
    }

    private val actions = object : OnboardingActions {
        override fun start() { stage = Stage.CODE }

        override fun setupAlone() {
            pageIndex = 0
            stage = Stage.CONSENT
        }

        override fun onCodeChange(value: String) {
            code = CloudSync.normalizeCode(value).take(6)
            error = null
        }

        override fun scan() { stage = Stage.SCAN }

        override fun onScanned(raw: String) {
            onCodeChange(extractSetupCode(raw))
            stage = Stage.CODE
            if (CloudSync.isCompleteCode(code)) submitCode()
        }

        override fun submitCode() {
            if (!CloudSync.isCompleteCode(code) || busy) return
            busy = true
            lifecycleScope.launch {
                runCatching { container.cloud.previewInvite(code) }
                    .onSuccess {
                        preview = it
                        stage = Stage.CONFIRM
                    }
                    .onFailure { error = friendlyError(it) }
                busy = false
            }
        }

        override fun confirm() {
            val p = preview ?: return
            busy = true
            lifecycleScope.launch {
                runCatching { container.cloud.claimInvite(code, p.helperName) }
                    .onSuccess {
                        pageIndex = 0
                        accepted = false
                        stage = Stage.CONSENT
                    }
                    .onFailure { error = friendlyError(it) }
                busy = false
            }
        }

        override fun decline() {
            preview = null
            code = ""
            stage = Stage.WELCOME
        }

        override fun turnOn() {
            val page = ConsentPages.all[pageIndex]
            accepted = true
            lifecycleScope.launch { container.store.update { it.copy(consent = it.consent + (page.key to true)) } }
            requestNextMissing()
        }

        override fun requestNextMissing() {
            val page = ConsentPages.all[pageIndex]
            val next = page.capabilities.firstOrNull { !container.capabilities.has(it) } ?: return
            request(next)
        }

        override fun notNow() {
            val page = ConsentPages.all[pageIndex]
            lifecycleScope.launch { container.store.update { it.copy(consent = it.consent + (page.key to false)) } }
            nextPage()
        }

        override fun nextPage() {
            if (pageIndex < ConsentPages.all.lastIndex) {
                pageIndex++
                accepted = container.store.current.consent[ConsentPages.all[pageIndex].key] == true
            } else {
                stage = Stage.DONE
            }
        }

        override fun finishSetup() {
            lifecycleScope.launch {
                val consent = container.store.update {
                    it.copy(onboardingDone = true, consentAcceptedAt = System.currentTimeMillis())
                }.consent
                container.cloud.reportConsent(consent, Features.CONSENT_VERSION)
                container.protection.reportStatus()
                container.reminders.reschedule()
                GuardianService.start(this@OnboardingActivity)
                startActivity(Intent(this@OnboardingActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                finish()
            }
        }
    }

    private fun request(c: Capability) {
        val runtime = when (c) {
            Capability.PHONE_STATE -> Manifest.permission.READ_PHONE_STATE
            Capability.CONTACTS -> Manifest.permission.READ_CONTACTS
            Capability.CALL_PHONE -> Manifest.permission.CALL_PHONE
            Capability.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
            else -> null
        }
        if (runtime != null) {
            permissionLauncher.launch(arrayOf(runtime))
            return
        }
        val intent = container.capabilities.settingsIntent(c) ?: return
        intent.flags = intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
        try {
            settingsLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            runCatching { settingsLauncher.launch(container.capabilities.fallbackSettingsIntent(c).apply { flags = 0 }) }
        } catch (_: SecurityException) {
            runCatching { settingsLauncher.launch(container.capabilities.fallbackSettingsIntent(c).apply { flags = 0 }) }
        }
    }

    private fun friendlyError(t: Throwable): String {
        val code = (t as? FirebaseFunctionsException)?.code
        return when (code) {
            FirebaseFunctionsException.Code.NOT_FOUND -> "That code didn't work. Please check it with your family member."
            FirebaseFunctionsException.Code.FAILED_PRECONDITION -> "That code has expired. Ask your family member for a new one."
            FirebaseFunctionsException.Code.UNAVAILABLE, FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ->
                "The phone isn't connected to the internet. Please connect to Wi-Fi and try again."
            else -> "Something went wrong. Please try again in a moment."
        }
    }
}
