package app.guardianmode.device

import android.app.Instrumentation
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import app.guardianmode.protection.Capability
import app.guardianmode.support.DeviceFixtures
import app.guardianmode.support.DeviceRule
import app.guardianmode.support.StubApp
import app.guardianmode.ui.onboarding.ConsentPages
import app.guardianmode.ui.onboarding.OnboardingActivity
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.regex.Pattern

/**
 * qa/antigravity/cases/02-setup-and-consent.md: the call screening role request on each Android
 * level the daily run has (29, 33, 36).
 *
 * The test starts mid-setup with everything granted except the screening role, walks the real
 * setup screens to page 3 and taps "Turn on". It checks where Guardian sent the parent (the
 * intent it started, recorded with an ActivityMonitor that lets it through, and the system app
 * that came to the front), what Android's dialog says, and that its "Set as default", as
 * Guardian's instruction says, brings the parent back to page 3 ticked.
 *
 * The home role, Usage access and Display over other apps (GM-CONS-003, 005, 006) had tests here
 * too, and were taken out: on API 33 and 36 every test after the first in this class reached
 * setup resumed but with a window UiAutomator couldn't read ("Skipping null root node"), through
 * six ways of opening it (PR #21). They stay not automated until that is understood.
 *
 * Runtime permission dialogs (GM-CONS-004, the rest of GM-CONS-007, GM-CONS-009) can't be
 * tested here: revoking a runtime permission, or the exact-alarm access, kills the process the
 * tests run in. qa/daily/device_checks.py walks those from outside the app.
 */
@RunWith(AndroidJUnit4::class)
class ConsentDeviceTest {
    @get:Rule val rule = DeviceRule(initialState = {
        // Mid-setup: nothing agreed yet and setup not finished, as when the parent reaches the consent pages.
        DeviceFixtures.soloState.copy(onboardingDone = false, consent = emptyMap())
    })

    private val started: MutableList<Intent> = Collections.synchronizedList(mutableListOf())

    /** Records every activity Guardian starts and lets it start (a monitor made with no filter sees them all). */
    private val recorder = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            started += Intent(intent)
            return null
        }
    }

    /**
     * Leaves no setup behind for the next test: setup marked done again (so Home shows the home
     * screen, not setup) and every setup screen this test opened closed.
     */
    @After fun tearDown() {
        rule.instrumentation.removeMonitor(recorder)
        rule.updateState { it.copy(onboardingDone = true) }
        OnboardingActivity.clearProgress(rule.context)
        closeSetup()
    }

    /** Finishes every setup screen this process has open. */
    private fun closeSetup() {
        runCatching {
            rule.withLimit(10_000) {
                rule.instrumentation.runOnMainSync {
                    val registry = ActivityLifecycleMonitorRegistry.getInstance()
                    Stage.values().flatMap { registry.getActivitiesInStage(it) }
                        .filterIsInstance<OnboardingActivity>().distinct().forEach { it.finish() }
                }
            }
        }.onFailure { Log.w(TAG, "couldn't close setup: $it") }
        rule.device.waitForIdle()
    }

    private val sdk = Build.VERSION.SDK_INT

    /** Opens setup fresh and walks to consent page [index] (0-based), saying "Not now" to the pages before it. */
    private fun openConsentPage(index: Int) {
        // The parent's way in: from another app, Home. Guardian's home screen comes back and,
        // since setup isn't done, sends the parent to setup. Starting setup any other way didn't
        // hold up on the CI emulators (PR #21): CLEAR_TASK cleared the home task and the old
        // launcher came up; NEW_TASK from the test brought nothing up; Home with the home screen
        // already resumed only gave it a new intent; and a second setup started next to the one
        // the home screen starts left a window UiAutomator couldn't read.
        // When setup is up but UiAutomator reads its window as empty ("Skipping null root node"
        // for the same window on every try, PR #21's API 36 run on 96dc835), the accessibility
        // cache is cleared before looking again.
        var opened = false
        closeSetup()
        for (attempt in 1..3) {
            OnboardingActivity.clearProgress(rule.context)
            rule.open(StubApp.NOTES)
            rule.waitForPackage(StubApp.NOTES.packageName)
            rule.device.pressHome()
            opened = rule.waitText("How will this phone be used?", 8_000)
            if (!opened) {
                rule.refreshAccessibility()
                opened = rule.waitText("How will this phone be used?", 5_000)
            }
            if (opened) break
            Log.w(TAG, "setup didn't show the role choice (try $attempt); top=${rule.topPackage()} " +
                "resumed=${rule.resumedActivity()?.javaClass?.simpleName} screen=${rule.screenSummary().take(400)}")
        }
        assertWithMessage("setup opened on the role choice (top=${rule.topPackage()}, resumed=${rule.resumedActivity()?.javaClass?.simpleName})").that(opened).isTrue()
        rule.tap("Make this phone simpler")
        assertThat(rule.waitText("Set up on my own for now")).isTrue()
        rule.tap("Set up on my own for now")
        repeat(index) { i ->
            assertWithMessage("page ${i + 1}").that(rule.waitText(ConsentPages.all[i].title)).isTrue()
            rule.tap("Not now")
        }
        assertWithMessage("page ${index + 1}").that(rule.waitText(ConsentPages.all[index].title)).isTrue()
        rule.instrumentation.addMonitor(recorder)
    }

    /** Taps "Turn on" and returns the one activity Guardian started for it. */
    private fun turnOn(): Intent {
        started.clear()
        rule.tap("Turn on")
        assertWithMessage("Guardian started an Android screen").that(rule.waitUntil { started.isNotEmpty() }).isTrue()
        val intent = started.first()
        Log.i(TAG, "API $sdk: Turn on started $intent role=${intent.getStringExtra(ROLE_NAME)} " +
            "resolves to ${rule.context.packageManager.resolveActivity(intent, 0)?.activityInfo?.let { "${it.packageName}/${it.name}" }}")
        return intent
    }

    /** Every text (and description) on screen that belongs to [packageName]. */
    private fun textsOf(packageName: String): List<String> =
        rule.device.findObjects(By.pkg(packageName)).mapNotNull { o ->
            runCatching { o.text?.takeIf { it.isNotBlank() } ?: o.contentDescription?.takeIf { it.isNotBlank() } }.getOrNull()
        }.distinct()

    /** Back in Guardian's setup on the same page, with every item ticked. */
    private fun assertBackAndTicked(page: Int, capability: Capability) {
        assertWithMessage("back in Guardian after Android's screen (top=${rule.topPackage()})").that(rule.waitForPackage(rule.pkg, 10_000)).isTrue()
        assertWithMessage("${capability.key} granted").that(rule.waitUntil { rule.container.capabilities.has(capability) }).isTrue()
        assertWithMessage("still on page ${page + 1}").that(rule.waitText(ConsentPages.all[page].title)).isTrue()
        assertWithMessage("\"All set\" once ${capability.plainName} is ticked").that(rule.waitText("All set", 10_000)).isTrue()
        assertThat(rule.seen(By.text("Next"))).isTrue()
    }

    /**
     * Guardian asks for [role] with Android's own role request (the permission controller), the
     * dialog names Guardian Mode, and its "Set as default" (what Guardian's instruction tells
     * the parent to tap) gives Guardian the role.
     */
    private fun acceptsRoleRequest(page: Int, role: String, capability: Capability, case: String) {
        openConsentPage(page)
        // Taken away once setup is open, since Home must open Guardian to get there.
        rule.shell("cmd role remove-role-holder $role ${rule.pkg}")
        assertWithMessage("$role taken away for the test").that(rule.waitUntil { !rule.container.capabilities.has(capability) }).isTrue()

        val intent = turnOn()
        assertThat(intent.action).isEqualTo(REQUEST_ROLE)
        assertThat(intent.getStringExtra(ROLE_NAME)).isEqualTo(role)
        val controller = intent.`package`
        assertWithMessage("role request goes to Android's permission controller").that(controller).isIn(PERMISSION_CONTROLLERS)
        assertWithMessage("Android's role dialog in front (top=${rule.topPackage()})").that(rule.waitForPackage(controller!!, 10_000)).isTrue()
        rule.waitFor(By.pkg(controller).text(SET_AS_DEFAULT), 5_000)
        val words = textsOf(controller)
        Log.i(TAG, "$case API $sdk: Android's role dialog says $words; Guardian's instruction: \"${ConsentPages.instruction(capability)}\"")
        assertWithMessage("the role dialog names Guardian Mode: $words").that(words.any { "Guardian Mode" in it }).isTrue()
        assertWithMessage("Guardian's instruction says \"Set as default\"").that(ConsentPages.instruction(capability)).contains("\"Set as default\"")
        // The dialog lists Guardian Mode (picked already when it asked); pick it anyway, as the instruction says.
        rule.device.findObject(By.pkg(controller).text("Guardian Mode"))?.let { runCatching { it.click() } }
        val button = rule.device.findObject(By.pkg(controller).text(SET_AS_DEFAULT))
        assertWithMessage("a \"Set as default\" button on Android's role dialog (it shows $words)").that(button).isNotNull()
        button.click()
        assertBackAndTicked(page, capability)
    }

    @Test fun gm_cons_007_screeningRoleOpensAndroidsDialogAndComesBackTicked() {
        acceptsRoleRequest(CALLS_PAGE, RoleManager.ROLE_CALL_SCREENING, Capability.CALL_SCREENING, "GM-CONS-007")
    }

    companion object {
        const val TAG = "ConsentDeviceTest"
        /** RoleManager.createRequestRoleIntent's action and extra (hidden from the public API). */
        const val REQUEST_ROLE = "android.app.role.action.REQUEST_ROLE"
        const val ROLE_NAME = "android.intent.extra.ROLE_NAME"
        const val CALLS_PAGE = 2
        val PERMISSION_CONTROLLERS = listOf("com.android.permissioncontroller", "com.google.android.permissioncontroller")
        /** Android 10's dialogs show buttons in capitals. */
        val SET_AS_DEFAULT: Pattern = Pattern.compile("(?i)set as default")
    }
}
