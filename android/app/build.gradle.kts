import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

// Firebase settings come from environment variables or local.properties so no project
// secrets live in the repo. Without them the app talks to the local Firebase emulators.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun setting(name: String, default: String = ""): String =
    System.getenv(name) ?: localProps.getProperty(name) ?: default

android {
    namespace = "app.guardianmode"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.guardianmode"
        minSdk = 29
        targetSdk = 36
        versionCode = (System.getenv("GUARDIAN_VERSION_CODE") ?: "1").toInt()
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${setting("GUARDIAN_FIREBASE_PROJECT_ID", "demo-guardian")}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${setting("GUARDIAN_FIREBASE_APP_ID", "1:000000000000:android:0000000000000000")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${setting("GUARDIAN_FIREBASE_API_KEY", "demo-api-key")}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${setting("GUARDIAN_FIREBASE_SENDER_ID", "000000000000")}\"")
        buildConfigField("String", "FIREBASE_STORAGE_BUCKET", "\"${setting("GUARDIAN_FIREBASE_STORAGE_BUCKET", "demo-guardian.appspot.com")}\"")
        buildConfigField("String", "EMULATOR_HOST", "\"${setting("GUARDIAN_EMULATOR_HOST", "10.0.2.2")}\"")
    }

    signingConfigs {
        create("release") {
            val storePath = System.getenv("GUARDIAN_UPLOAD_KEYSTORE")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = System.getenv("GUARDIAN_UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GUARDIAN_UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("GUARDIAN_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("Boolean", "USE_EMULATORS", "${setting("GUARDIAN_USE_EMULATORS", "true")}")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("Boolean", "USE_EMULATORS", "false")
            if (System.getenv("GUARDIAN_UPLOAD_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.systemProperty("robolectric.pixelCopyRenderMode", "hardware") }
        }
        animationsDisabled = true
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkReleaseBuilds = true
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

dependencies {
    implementation("app.guardianmode:guardian-core")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.functions)
    implementation(libs.firebase.messaging)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.rule)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.truth)
}
