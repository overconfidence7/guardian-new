package app.guardianmode.data

import app.guardianmode.core.DeviceConfig
import app.guardianmode.core.HomeLayout
import app.guardianmode.core.HomeTile
import app.guardianmode.core.TileKind
import java.util.TimeZone

/** What the phone shows before a helper has configured anything. Useful on its own. */
object DefaultConfig {
    val config: DeviceConfig
        get() = DeviceConfig(
            home = HomeLayout(
                tiles = listOf(
                    HomeTile("phone", TileKind.PHONE, label = "Phone"),
                    HomeTile("messages", TileKind.MESSAGES, label = "Messages"),
                    HomeTile("camera", TileKind.CAMERA, label = "Camera"),
                    HomeTile("photos", TileKind.PHOTOS, label = "Photos"),
                    HomeTile("reminders", TileKind.REMINDERS, label = "Reminders"),
                    HomeTile("help", TileKind.HELP, label = "Help"),
                ),
            ),
            checkIn = DeviceConfig().checkIn.copy(timezone = TimeZone.getDefault().id),
        )
}
