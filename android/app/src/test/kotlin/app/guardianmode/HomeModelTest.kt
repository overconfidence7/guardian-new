package app.guardianmode

import app.guardianmode.core.HomeTile
import app.guardianmode.core.TileKind
import app.guardianmode.data.HelperInfo
import app.guardianmode.data.PhotoItem
import app.guardianmode.ui.home.HomeModel
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HomeModelTest {
    private val now = Fixtures.now

    @Test fun `person tiles carry the phone number to call`() {
        val ui = HomeModel.build(Fixtures.state, now)
        val sarah = ui.tiles.first()
        assertThat(sarah.kind).isEqualTo(TileKind.CALL_CONTACT)
        assertThat(sarah.label).isEqualTo("Sarah")
        assertThat(sarah.phone).isEqualTo("+1 555 010 1111")
    }

    @Test fun `tiles pointing at missing people or apps are dropped`() {
        val config = Fixtures.config.copy(
            home = Fixtures.config.home.copy(
                tiles = listOf(HomeTile("x", TileKind.CALL_CONTACT, "nobody"), HomeTile("y", TileKind.APP, "com.not.installed")),
            ),
        )
        val ui = HomeModel.build(Fixtures.state.copy(config = config), now, appLabel = { null })
        assertThat(ui.tiles).isEmpty()
    }

    @Test fun `next reminder appears within two hours only`() {
        val ui = HomeModel.build(Fixtures.state, now)
        assertThat(ui.nextReminder?.first).isEqualTo("Blood pressure pill")
        val later = HomeModel.build(Fixtures.state, now.withHour(13))
        assertThat(later.nextReminder).isNull()
    }

    @Test fun `greeting follows the time of day`() {
        assertThat(HomeModel.build(Fixtures.state, now).greeting).isEqualTo("Good morning, Margaret")
        assertThat(HomeModel.build(Fixtures.state, now.withHour(19)).greeting).isEqualTo("Good evening, Margaret")
    }

    @Test fun `photo title says who sent it and when`() {
        val photo = PhotoItem("p", "u", "Emma", "", now.minusHours(1).toInstant().toEpochMilli())
        assertThat(HomeModel.photoTitle(photo, now)).isEqualTo("Today from Emma")
        val old = photo.copy(createdAt = now.minusDays(1).toInstant().toEpochMilli())
        assertThat(HomeModel.photoTitle(old, now)).isEqualTo("Yesterday from Emma")
    }

    @Test fun `pending helper is surfaced for approval`() {
        val state = Fixtures.state.copy(helpers = Fixtures.state.helpers + HelperInfo("u3", "Rick", status = "pending"))
        assertThat(HomeModel.build(state, now).pendingHelper?.name).isEqualTo("Rick")
    }

    @Test fun `paused state is reflected`() {
        val paused = Fixtures.state.copy(pausedUntil = now.plusMinutes(30).toInstant().toEpochMilli())
        assertThat(HomeModel.build(paused, now).paused).isTrue()
    }
}
