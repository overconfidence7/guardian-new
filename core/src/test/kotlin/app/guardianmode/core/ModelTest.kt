package app.guardianmode.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelTest {
    @Test fun `empty document decodes to safe defaults`() {
        val c = GuardianJson.decodeConfig("{}")
        assertTrue(c.protection.pauseTripwires.enabled)
        assertEquals(ScreeningMode.SILENCE_UNKNOWN, c.protection.callScreening.mode)
    }

    @Test fun `unknown fields and enum values from newer dashboards are tolerated`() {
        val c = GuardianJson.decodeConfig(
            """{"futureField":1,"display":{"theme":"NEON","fontScale":1.5},"protection":{"callScreening":{"mode":"SOMETHING_NEW"}}}""",
        )
        assertEquals(1.5f, c.display.fontScale)
        assertEquals(ThemePreference.LIGHT, c.display.theme)
        assertEquals(ScreeningMode.SILENCE_UNKNOWN, c.protection.callScreening.mode)
    }

    @Test fun `config round-trips`() {
        val c = DeviceConfig(
            parentName = "Mom",
            family = listOf(FamilyContact("f", "Sarah", phone = "555", isPrimaryHelper = true)),
            reminders = listOf(Reminder("r", "Pill", times = listOf("08:00"))),
        )
        assertEquals(c, GuardianJson.decodeConfig(GuardianJson.encodeConfig(c)))
    }

    @Test fun `primary helper falls back to first family member with a phone`() {
        val c = DeviceConfig(family = listOf(FamilyContact("a", "A"), FamilyContact("b", "B", phone = "1")))
        assertEquals("B", c.primaryHelper?.name)
    }

    @Test fun `shared fixture matches the web contract`() {
        val text = javaClass.getResource("/device-config.example.json")!!.readText()
        val c = GuardianJson.decodeConfig(text)
        assertEquals("Margaret", c.parentName)
        assertTrue(c.family.isNotEmpty())
        assertEquals(c, GuardianJson.decodeConfig(GuardianJson.encodeConfig(c)))
    }

    @Test fun `transparency lists every feature and never-collected items`() {
        val items = Transparency.items(DeviceConfig())
        assertEquals(7, items.size)
        assertTrue(Transparency.NEVER_COLLECTED.any { it.contains("messages") })
    }
}
