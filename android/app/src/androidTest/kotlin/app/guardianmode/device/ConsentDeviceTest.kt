package app.guardianmode.device

import android.app.Instrumentation
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import app.guardianmode.protection.Capability
import app.guardianmode.support.DeviceFixtures
import app.guardianmode.support.DeviceRule
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
 * qa/antigravity/cases/02-setup-and-consent.md: the Android screens setup sends the parent to
 * for roles and special access, on each Android level the daily run has (29, 33, 36).
 *
 * Every test starts mid-setup with everything granted except the one thing it is about, walks
 * the real setup screens to that page and taps "Turn on". It then checks where Guardian sent
 * the parent: the intent it started (recorded with an ActivityMonitor, which lets it through)
 * and the system app that came to the front, with what that screen says. Roles are accepted on
 * Android's own dialog with "Set as default", as Guardian's instruction says. A Settings switch
 * is turned on with appops instead of a tap, since where the switch sits differs per level;
 * then Back must land in setup with the item ticked.
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
        // Setup is opened by Guardian's home screen itself, as MainActivity does while setup isn't
        // done. Not by pressing Home: when the home screen was already resumed, Home only brought
        // it a new intent and it never re-read that setup isn't done. Not from the test's context
        // either: with NEW_TASK nothing came up over the home screen (both on PR #21's API 36
        // runs), and with CLEAR_TASK the home task was cleared and the old launcher came up.
        closeSetup()
        OnboardingActivity.clearProgress(rule.context)
        var opened = false
        for (attempt in 1..3) {
            rule.device.pressHome()
            rule.device.waitForIdle()
            val home = rule.resumedActivity()
            if (home != null) {
                rule.withLimit(10_000) { rule.instrumentation.runOnMainSync { home.startActivity(Intent(home, OnboardingActivity::class.java)) } }
            } else {
                rule.context.startActivity(Intent(rule.context, OnboardingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            opened = rule.waitText("How will this phone be used?", 8_000)
            if (opened) break
            Log.w(TAG, "setup didn't show the role choice (try $attempt, started from ${home?.javaClass?.simpleName}); " +
                "top=${rule.topPackage()} resumed=${rule.resumedActivity()?.javaClass?.simpleName} screen=${rule.screenSummary().take(400)}")
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

    /** True when [text] shows in [packageName]'s window, scrolling its list a few times if needed (Settings lists). */
    private fun shownIn(packageName: String, text: String): Boolean {
        val selector = By.pkg(packageName).text(text)
        if (rule.waitFor(selector, 4_000)) return true
        repeat(6) {
            val list = rule.device.findObject(By.pkg(packageName).scrollable(true)) ?: return false
            val more = runCatching { list.scroll(Direction.DOWN, 0.8f) }.getOrDefault(false)
            if (rule.has(selector)) return true
            if (!more) return false
        }
        return false
    }

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

    /** Opens Settings from page 2 for [capability] (the other item on the page is granted) and returns what Guardian started. */
    private fun openSpecialAccess(capability: Capability, op: String): Intent {
        rule.shell("appops set ${rule.pkg} $op deny")
        assertWithMessage("$op taken away for the test").that(rule.waitUntil { !rule.container.capabilities.has(capability) }).isTrue()
        openConsentPage(PAUSE_PAGE)
        val intent = turnOn()
        assertWithMessage("Settings in front (top=${rule.topPackage()})").that(rule.waitForPackage(SETTINGS, 10_000)).isTrue()
        return intent
    }

    /** The Settings screen that resumed since [since], as Usage access saw it (the class says list or Guardian's own page). */
    private fun settingsScreenSince(since: Long): String =
        rule.resumesSince(since).lastOrNull { it.first == SETTINGS }?.second ?: "?"

    @Test fun gm_cons_003_homeRoleOpensAndroidsDialogAndComesBackTicked() {
        acceptsRoleRequest(HOME_PAGE, RoleManager.ROLE_HOME, Capability.HOME_APP, "GM-CONS-003")
        // Pressing Home now opens Guardian, which stays in setup until it's finished.
        assertThat(rule.waitUntil { rule.homeResolvesTo().startsWith("${rule.pkg}/") }).isTrue()
    }

    @Test fun gm_cons_005_usageAccessDeepLinkOpensSettingsForGuardian() {
        val since = System.currentTimeMillis()
        val intent = openSpecialAccess(Capability.USAGE_ACCESS, "GET_USAGE_STATS")
        assertThat(intent.action).isEqualTo(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        // Guardian asks for its own entry; where Android honors that, it opens on Guardian's switch.
        assertThat(intent.data).isEqualTo(Uri.fromParts("package", rule.pkg, null))
        assertWithMessage("Settings shows Guardian Mode (${textsOf(SETTINGS)})").that(shownIn(SETTINGS, "Guardian Mode")).isTrue()
        val words = textsOf(SETTINGS)
        // The parent turns the switch on (a tap whose place differs per level), then presses Back.
        rule.shell("appops set ${rule.pkg} GET_USAGE_STATS allow")
        val screen = settingsScreenSince(since)
        Log.i(TAG, "GM-CONS-005 API $sdk: Usage access opened $screen; it shows $words; instruction: \"${ConsentPages.instruction(Capability.USAGE_ACCESS)}\"")
        assertWithMessage("Usage access resumed in Settings").that(screen).isNotEqualTo("?")
        rule.device.pressBack()
        assertBackAndTicked(PAUSE_PAGE, Capability.USAGE_ACCESS)
    }

    @Test fun gm_cons_006_displayOverOtherAppsOpensSettingsForGuardian() {
        val since = System.currentTimeMillis()
        val intent = openSpecialAccess(Capability.OVERLAY, "SYSTEM_ALERT_WINDOW")
        assertThat(intent.action).isEqualTo(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        assertThat(intent.data).isEqualTo(Uri.fromParts("package", rule.pkg, null))
        assertWithMessage("Settings shows Guardian Mode (${textsOf(SETTINGS)})").that(shownIn(SETTINGS, "Guardian Mode")).isTrue()
        val words = textsOf(SETTINGS)
        val instruction = ConsentPages.instruction(Capability.OVERLAY)
        if (sdk < 30) {
            // Android 10 honors the package and opens Guardian's own switch, which the instruction names.
            assertWithMessage("Guardian's own \"Display over other apps\" page ($words)")
                .that(shownIn(SETTINGS, "Allow display over other apps")).isTrue()
        } else {
            // From Android 11 the package is ignored and the parent lands on the list of apps,
            // so the instruction has to say to find Guardian Mode there first.
            assertWithMessage("instruction on Android $sdk tells the parent to find Guardian Mode in the list")
                .that(instruction).containsMatch("Guardian Mode.*list|list.*Guardian Mode")
        }
        rule.shell("appops set ${rule.pkg} SYSTEM_ALERT_WINDOW allow")
        val screen = settingsScreenSince(since)
        Log.i(TAG, "GM-CONS-006 API $sdk: overlay opened $screen; it shows $words; instruction: \"$instruction\"")
        if (sdk >= 30) assertWithMessage("Settings screen on Android $sdk is the list, as the instruction says").that(screen).doesNotContain("AppDrawOverlay")
        rule.device.pressBack()
        assertBackAndTicked(PAUSE_PAGE, Capability.OVERLAY)
    }

    @Test fun gm_cons_007_screeningRoleOpensAndroidsDialogAndComesBackTicked() {
        acceptsRoleRequest(CALLS_PAGE, RoleManager.ROLE_CALL_SCREENING, Capability.CALL_SCREENING, "GM-CONS-007")
    }

    companion object {
        const val TAG = "ConsentDeviceTest"
        const val SETTINGS = "com.android.settings"
        /** RoleManager.createRequestRoleIntent's action and extra (hidden from the public API). */
        const val REQUEST_ROLE = "android.app.role.action.REQUEST_ROLE"
        const val ROLE_NAME = "android.intent.extra.ROLE_NAME"
        const val HOME_PAGE = 0
        const val PAUSE_PAGE = 1
        const val CALLS_PAGE = 2
        val PERMISSION_CONTROLLERS = listOf("com.android.permissioncontroller", "com.google.android.permissioncontroller")
        /** Android 10's dialogs show buttons in capitals. */
        val SET_AS_DEFAULT: Pattern = Pattern.compile("(?i)set as default")
    }
}
