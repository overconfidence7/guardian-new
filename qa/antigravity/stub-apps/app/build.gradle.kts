plugins {
    id("com.android.application")
}

// One flavor per stand-in app: package name Guardian watches, and the label Android shows.
// Package names must match core/src/main/kotlin/app/guardianmode/core/AppCatalog.kt.
val stubs = mapOf(
    "chase" to ("com.chase.sig.android" to "Chase"),               // BANK
    "venmo" to ("com.venmo" to "Venmo"),                           // PAYMENT
    "zelle" to ("com.zellepay.zelle" to "Zelle"),                  // PAYMENT
    "target" to ("com.target.ui" to "Target"),                     // GIFT_CARD
    "coinbase" to ("com.coinbase.android" to "Coinbase"),          // CRYPTO
    "anydesk" to ("com.anydesk.anydeskandroid" to "AnyDesk"),      // REMOTE_ACCESS
    "teamviewer" to ("com.teamviewer.quicksupport.market" to "TeamViewer QuickSupport"), // REMOTE_ACCESS
    "rustdesk" to ("com.carriez.flutter_hbb" to "RustDesk"),       // REMOTE_ACCESS
    "creditunion" to ("org.guardianqa.hometowncu" to "Hometown CU"), // not built in; family adds it
    "notes" to ("org.guardianqa.notes" to "Notes"),                // neutral app for lock tests
    "screenhelper" to ("org.guardianqa.screenhelper" to "Screen Helper"), // declares an accessibility service
)

android {
    namespace = "app.guardianqa.stub"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0-stub"
    }

    flavorDimensions += "app"
    productFlavors {
        stubs.forEach { (flavor, info) ->
            create(flavor) {
                dimension = "app"
                applicationId = info.first
                resValue("string", "app_name", info.second)
            }
        }
    }

    buildFeatures {
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
