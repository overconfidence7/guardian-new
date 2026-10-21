package app.guardianmode.support

/**
 * The stand-in apps from qa/antigravity/stub-apps, installed under the real package
 * names Guardian watches. CI builds them, installs the [preinstalled] ones before the
 * device suite runs, and pushes every APK to /data/local/tmp/stubs/<flavor>.apk so a
 * test can install or reinstall one itself (install alarms, updates).
 */
enum class StubApp(val packageName: String, val label: String, val flavor: String, val preinstalled: Boolean = true) {
    CHASE("com.chase.sig.android", "Chase", "chase"),
    VENMO("com.venmo", "Venmo", "venmo"),
    ZELLE("com.zellepay.zelle", "Zelle", "zelle"),
    TARGET("com.target.ui", "Target", "target"),
    COINBASE("com.coinbase.android", "Coinbase", "coinbase"),
    ANYDESK("com.anydesk.anydeskandroid", "AnyDesk", "anydesk", preinstalled = false),
    TEAMVIEWER("com.teamviewer.quicksupport.market", "TeamViewer QuickSupport", "teamviewer", preinstalled = false),
    RUSTDESK("com.carriez.flutter_hbb", "RustDesk", "rustdesk", preinstalled = false),
    CREDIT_UNION("org.guardianqa.hometowncu", "Hometown CU", "creditunion"),
    NOTES("org.guardianqa.notes", "Notes", "notes"),
    SCREEN_HELPER("org.guardianqa.screenhelper", "Screen Helper", "screenhelper", preinstalled = false),
    ;

    /** Where CI pushed this stand-in's APK on the device. */
    val apkOnDevice: String get() = "/data/local/tmp/stubs/$flavor.apk"

    /** Every flavor shares the stub's one activity (namespace app.guardianqa.stub). */
    val component: String get() = "$packageName/app.guardianqa.stub.StubActivity"
}
