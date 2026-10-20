package app.guardianmode

import app.guardianmode.cloud.ChangeRequest
import app.guardianmode.cloud.ChangeRequests
import app.guardianmode.cloud.ConfigMerge
import app.guardianmode.core.DeviceConfig
import app.guardianmode.core.GuardianJson
import app.guardianmode.data.HelperInfo
import app.guardianmode.ui.home.HomeModel
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class ChangeRequestTest {
    private fun json(c: DeviceConfig) = GuardianJson.json.encodeToJsonElement(DeviceConfig.serializer(), c).jsonObject

    @Test fun `an allowed request changes exactly what was asked`() {
        val config = Fixtures.config
        val updated = ChangeRequests.apply(
            config,
            mapOf(
                "pauseTripwiresEnabled" to false,
                "trustedPackages" to listOf("com.anydesk.anydeskandroid"),
                "remoteUnlockUntil" to 1_800_000_000_000L,
                "family" to listOf(mapOf("id" to "fam_x", "name" to "David", "phone" to "+1 555 010 2222", "isPrimaryHelper" to true)),
                "somethingElse" to "ignored",
            ),
        )
        assertThat(updated.protection.pauseTripwires.enabled).isFalse()
        assertThat(updated.protection.remoteAccessAlarm.trustedPackages).containsExactly("com.anydesk.anydeskandroid")
        assertThat(updated.lock.remoteUnlockUntil).isEqualTo(1_800_000_000_000L)
        assertThat(updated.family.single().isPrimaryHelper).isTrue()
        assertThat(updated.copy(protection = config.protection, lock = config.lock, family = config.family)).isEqualTo(config)
    }

    @Test fun `the parent is asked in plain words built from the change`() {
        assertThat(ChangeRequests.describe(mapOf("pauseTripwiresEnabled" to false), 0))
            .isEqualTo("turn off the pause before sending money")
        assertThat(ChangeRequests.describe(mapOf("trustedPackages" to emptyList<String>(), "remoteUnlockUntil" to 10 * 60_000L), 0))
            .isEqualTo("let a remote-control app open without a warning and unlock your phone for 10 minutes")
    }

    @Test fun `the home screen shows a request only from an approved helper, under their own name`() {
        val state = Fixtures.state.copy(helpers = listOf(HelperInfo("u1", "Sarah"), HelperInfo("u9", "Stranger", status = "pending")))
        val requests = listOf(
            ChangeRequest("r0", "u9", "Sarah", mapOf("longCallAlertEnabled" to false)),
            ChangeRequest("r1", "u1", "Your bank", mapOf("longCallAlertEnabled" to false)),
        )
        val ui = HomeModel.build(state, Fixtures.now, requests = requests).pendingRequest!!
        assertThat(ui.id).isEqualTo("r1")
        assertThat(ui.name).isEqualTo("Sarah")
        assertThat(ui.what).isEqualTo("stop telling your family about long calls with unknown numbers")
    }

    @Test fun `the phone's push keeps a helper's edit to another setting`() {
        val base = Fixtures.config
        val local = base.copy(protection = base.protection.copy(longCallAlert = base.protection.longCallAlert.copy(enabled = false)))
        val remote = base.copy(display = base.display.copy(fontScale = 1.8f))
        val merged = ConfigMerge.threeWay(json(base), json(local), json(remote))!!
        val decoded = GuardianJson.json.decodeFromJsonElement(DeviceConfig.serializer(), merged)
        assertThat(decoded.protection.longCallAlert.enabled).isFalse()
        assertThat(decoded.display.fontScale).isEqualTo(1.8f)
    }

    @Test fun `where both changed the same setting the parent wins`() {
        val base = Fixtures.config
        val local = base.copy(display = base.display.copy(fontScale = 1.5f))
        val remote = base.copy(display = base.display.copy(fontScale = 1.8f))
        val merged = ConfigMerge.threeWay(json(base), json(local), json(remote))!!
        assertThat(GuardianJson.json.decodeFromJsonElement(DeviceConfig.serializer(), merged).display.fontScale).isEqualTo(1.5f)
    }
}
