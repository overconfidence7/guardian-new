package app.guardianmode.core

import app.guardianmode.core.PhoneSim.Companion.ANYDESK
import app.guardianmode.core.PhoneSim.Companion.CHASE
import app.guardianmode.core.PhoneSim.Companion.NOTES
import app.guardianmode.core.PhoneSim.Companion.TEAMVIEWER
import app.guardianmode.core.PhoneSim.Companion.UNKNOWN
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * qa/antigravity/cases/07-remote-control-alarm.md, the rule parts (docs/SPEC.md F5,
 * TripwireEngine.onPackageInstalled and the remote-access branch of onForeground).
 * Which installs Android reports (already there, updates) is InstallWatcher's job.
 */
class RemoteControlAlarmTest {

    @Test fun gm_racc_001_installingAnyDeskAlarmsUrgently() {
        val phone = PhoneSim()
        val actions = phone.install(ANYDESK, "AnyDesk", controlCapable = true)
        val warning = actions.remoteWarnings().single()
        assertTrue(warning.justInstalled)
        assertTrue(warning.knownRemoteAccessApp)
        assertEquals("AnyDesk", warning.appLabel)
        val event = actions.shared().single()
        assertEquals(EventType.REMOTE_ACCESS_INSTALLED, event.type)
        assertEquals(Severity.URGENT, event.severity)
        assertEquals(mapOf("app" to "AnyDesk", "afterUnknownCall" to "false"), event.data)
    }

    @Test fun gm_racc_002_installingDuringAnUnknownCall() {
        val phone = PhoneSim()
        phone.ring(UNKNOWN)
        phone.answer()
        phone.talkMinutes(1)
        val event = phone.install(TEAMVIEWER, "TeamViewer").shared().single()
        assertEquals("true", event.data["afterUnknownCall"])
        assertEquals(Severity.URGENT, event.severity)
        assertTrue(phone.open(CHASE, "Chase").pauseCards().single().duringCall)
        // A risky app was opened during the call, so the long-call notice is urgent (GM-CALL-003).
        val long = phone.talkMinutes(20).shared().single { it.type == EventType.LONG_UNKNOWN_CALL }
        assertEquals(Severity.URGENT, long.severity)
    }

    @Test fun gm_racc_005_openingWarnsThenNotAgainForTenMinutes() {
        val phone = PhoneSim()
        phone.install(ANYDESK, "AnyDesk")
        // "I trust this app" on the phone only dismisses the warning.
        val first = phone.open(ANYDESK, "AnyDesk")
        assertFalse(first.remoteWarnings().single().justInstalled)
        val opened = first.shared().single()
        assertEquals(EventType.REMOTE_ACCESS_OPENED, opened.type)
        assertEquals(Severity.NOTICE, opened.severity, "no call: NOTICE")
        assertEquals("false", opened.data["afterUnknownCall"])

        phone.goHome()
        phone.advanceMinutes(9)
        assertTrue(phone.open(ANYDESK, "AnyDesk").isEmpty(), "within 10 minutes: no warning")
        phone.goHome()
        phone.advanceMinutes(2)
        assertTrue(phone.open(ANYDESK, "AnyDesk").remoteWarnings().isNotEmpty(), "11 minutes after the first: warns again")
    }

    @Test fun gm_racc_005_openingWithinTheWindowAfterAnUnknownCallIsUrgent() {
        val phone = PhoneSim()
        phone.standardCall()
        phone.advanceMinutes(10)
        val event = phone.open(ANYDESK, "AnyDesk").shared().single()
        assertEquals(Severity.URGENT, event.severity)
        assertEquals("true", event.data["afterUnknownCall"])
    }

    @Test fun gm_racc_007_iTrustThisAppOnThePhoneOnlyDismisses() {
        val phone = PhoneSim()
        assertTrue(phone.open(ANYDESK, "AnyDesk").remoteWarnings().isNotEmpty())
        // There is no "trust" outcome in the rules: the trusted list is only changed by helpers.
        phone.goHome()
        phone.advanceMinutes(11)
        assertTrue(phone.open(ANYDESK, "AnyDesk").remoteWarnings().isNotEmpty())
    }

    @Test fun gm_racc_009_appAlreadyThereStillWarnsWhenOpened() {
        // No install was ever reported for it (InstallWatcher seeds pre-existing apps).
        val phone = PhoneSim()
        assertEquals(EventType.REMOTE_ACCESS_OPENED, phone.open(ANYDESK, "AnyDesk").shared().single().type)
    }

    /** Rule part: the engine has no memory of removals, so every install it is told about alarms. */
    @Test fun gm_racc_011_reinstallAfterRemovalAlarmsAgain() {
        val phone = PhoneSim()
        assertTrue(phone.install(ANYDESK, "AnyDesk").remoteWarnings().isNotEmpty())
        phone.advanceMinutes(1)
        assertTrue(phone.install(ANYDESK, "AnyDesk").remoteWarnings().isNotEmpty())
    }

    @Test fun gm_racc_012_appThatCanControlTheScreenGetsTheMilderWarning() {
        val phone = PhoneSim()
        val quiet = phone.install("com.example.screenhelper", "Screen Helper", controlCapable = true)
        val warning = quiet.remoteWarnings().single()
        assertFalse(warning.knownRemoteAccessApp, "the milder wording")
        val event = quiet.shared().single()
        assertEquals(EventType.CONTROL_APP_INSTALLED, event.type)
        assertEquals(Severity.NOTICE, event.severity)
        // Opening it later doesn't warn: only known remote-control apps warn on open.
        assertTrue(phone.open("com.example.screenhelper", "Screen Helper").isEmpty())

        phone.goHome()
        phone.standardCall()
        val inWindow = phone.install("com.example.screenhelper2", "Screen Helper 2", controlCapable = true).shared().single()
        assertEquals(Severity.URGENT, inWindow.severity)
        assertEquals("true", inWindow.data["afterUnknownCall"])
    }

    @Test fun gm_racc_013_ordinaryNewAppDoesNotAlarm() {
        val phone = PhoneSim()
        assertTrue(phone.install(NOTES, "Notes", controlCapable = false).isEmpty())
        assertTrue(phone.open(NOTES, "Notes").isEmpty())
    }

    @Test fun gm_racc_014_alarmSwitchedOffAndBackOn() {
        val phone = PhoneSim()
        phone.config = DeviceConfig(protection = ProtectionSettings(remoteAccessAlarm = RemoteAccessSettings(enabled = false)))
        assertTrue(phone.install(TEAMVIEWER, "TeamViewer", controlCapable = true).isEmpty())
        assertTrue(phone.install("com.example.screenhelper", "Screen Helper", controlCapable = true).isEmpty())
        assertTrue(phone.open(ANYDESK, "AnyDesk").isEmpty())
        assertTrue(phone.events.isEmpty())

        phone.config = DeviceConfig()
        phone.goHome()
        phone.advanceMinutes(1)
        // Installs made while it was off are not flagged later: nothing is queued.
        assertTrue(phone.talk(60_000).isEmpty())
        assertTrue(phone.open(ANYDESK, "AnyDesk").remoteWarnings().isNotEmpty())
    }

    @Test fun gm_racc_017_builtInListCoversTheSpecAndMatchesTheManifestQueries() {
        val catalog = AppCatalog()
        val remote = catalog.packagesIn(AppCategory.REMOTE_ACCESS)
        // The apps docs/SPEC.md F5 names.
        val named = mapOf(
            "AnyDesk" to "com.anydesk.anydeskandroid",
            "TeamViewer QuickSupport" to "com.teamviewer.quicksupport.market",
            "RustDesk" to "com.carriez.flutter_hbb",
            "Splashtop SOS" to "com.splashtop.sos",
            "Zoho Assist" to "com.zoho.assist.agent",
            "ScreenConnect" to "com.screenconnect.androidclient",
            "AirDroid" to "com.sand.airdroid",
            "Chrome Remote Desktop" to "com.google.chromeremotedesktop",
        )
        named.forEach { (name, pkg) -> assertTrue(pkg in remote, "$name ($pkg) is in AppCatalog") }
        remote.forEach { assertTrue(catalog.isRemoteAccess(it, ProtectionSettings()), it) }

        // Android hides packages that aren't declared in <queries>, so every entry must be in both.
        val manifest = File("../android/app/src/main/AndroidManifest.xml")
        assertTrue(manifest.isFile, "manifest not found at ${manifest.absolutePath}")
        val text = manifest.readText()
        val queries = Regex("(?s)<queries>(.*?)</queries>").find(text)?.groupValues?.get(1)
            ?: error("no <queries> block in the manifest")
        val declared = Regex("""<package\s+android:name="([^"]+)"""").findAll(queries).map { it.groupValues[1] }.toSet()
        assertEquals(emptySet(), remote - declared, "in AppCatalog but missing from the manifest's <queries>")
        assertEquals(emptySet(), declared - remote, "in the manifest's <queries> but not a remote-access app in AppCatalog")
    }

    @Test fun gm_racc_017_everyBuiltInRemoteAppWarnsOnInstallAndOpen() {
        for (pkg in AppCatalog().packagesIn(AppCategory.REMOTE_ACCESS)) {
            val phone = PhoneSim()
            assertEquals(EventType.REMOTE_ACCESS_INSTALLED, phone.install(pkg, pkg).shared().single().type, pkg)
            assertEquals(EventType.REMOTE_ACCESS_OPENED, phone.open(pkg, pkg).shared().single().type, pkg)
        }
    }
}
