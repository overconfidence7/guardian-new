package app.guardianmode

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.guardianmode.cloud.CloudSync
import app.guardianmode.data.LocalState
import app.guardianmode.data.Pairing
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * qa/antigravity/cases/17-performance-and-battery.md GM-PERF-007, the part a JVM can prove:
 * the Firebase SDKs (Messaging's eager start, Firestore's cache) are a large share of the
 * home screen's memory, so nothing Firebase is set up until the phone is paired. The PSS
 * budget itself is measured on the emulator by qa/daily/device_checks.py.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CloudStartupTest {

    @get:Rule val tmp = TemporaryFolder()

    private val app get() = ApplicationProvider.getApplicationContext<GuardianApp>()

    /** GuardianApp.onCreate has already run on a fresh, unpaired store when this starts. */
    @Test fun gm_perf_007_noFirebaseBeforePairing() {
        val cloud = app.container.cloud
        assertThat(app.container.store.current.pairing).isNull()
        assertThat(cloud.firebaseInitialized).isFalse()

        // Neither the app-start path nor a stray push wake-up brings Firebase up while unpaired.
        assertThat(cloud.initializeIfPaired()).isFalse()
        cloud.start()
        assertThat(cloud.firebaseInitialized).isFalse()
    }

    /** A phone that was paired before this app start still gets Firebase the moment the app starts. */
    @Test fun gm_perf_007_pairedPhoneInitializesAtStart() {
        val (store, scope) = UiChecks.openStore(app, tmp.newFolder("paired"))
        try {
            runBlocking { store.update { LocalState(pairing = Pairing("fam", "dev", "Sarah"), onboardingDone = true) } }
            val cloud = CloudSync(app, store, scope)
            assertThat(cloud.firebaseInitialized).isFalse()
            assertThat(cloud.initializeIfPaired()).isTrue()
            assertThat(cloud.firebaseInitialized).isTrue()
        } finally {
            UiChecks.close(scope)
        }
    }
}
