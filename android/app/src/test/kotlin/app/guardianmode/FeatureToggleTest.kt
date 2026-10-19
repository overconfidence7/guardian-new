package app.guardianmode

import app.guardianmode.core.ScreeningMode
import app.guardianmode.core.Transparency
import app.guardianmode.protection.withFeature
import app.guardianmode.ui.cards.CardRequest
import app.guardianmode.core.GuardianAction
import app.guardianmode.core.AppCategory
import app.guardianmode.cloud.CloudSync
import app.guardianmode.ui.onboarding.extractSetupCode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FeatureToggleTest {
    @Test fun `every toggle on the transparency screen changes the config`() {
        val keys = listOf("pause", "remoteAccess", "longCall", "screening", "checkIn")
        keys.forEach { key ->
            val off = Fixtures.config.withFeature(key, false)
            val item = Transparency.items(off).single { it.key == key }
            assertThat(item.enabled).isFalse()
            val on = off.withFeature(key, true)
            assertThat(Transparency.items(on).single { it.key == key }.enabled).isTrue()
        }
    }

    @Test fun `turning screening back on uses the gentle mode`() {
        val on = Fixtures.config.withFeature("screening", false).withFeature("screening", true)
        assertThat(on.protection.callScreening.mode).isEqualTo(ScreeningMode.SILENCE_UNKNOWN)
    }

    @Test fun `card requests map from engine actions`() {
        val req = CardRequest.from(GuardianAction.ShowPauseCard(AppCategory.CRYPTO, "com.coinbase.android", "Coinbase", true, 0))!!
        assertThat(req.notificationTitle).isEqualTo("Hold on a minute")
        assertThat(req.duringCall).isTrue()
        assertThat(CardRequest.from(GuardianAction.ShowSafeWordCard)).isNotNull()
    }

    @Test fun `setup codes are normalized from typing and QR links`() {
        assertThat(CloudSync.normalizeCode("ab-c 2d9")).isEqualTo("ABC2D9")
        assertThat(CloudSync.isCompleteCode("abc2d9")).isTrue()
        assertThat(CloudSync.normalizeCode("o0i1l")).isEmpty()
        assertThat(extractSetupCode("https://guardianmode.app/setup/K7PQ2M?src=qr")).isEqualTo("K7PQ2M")
    }
}
