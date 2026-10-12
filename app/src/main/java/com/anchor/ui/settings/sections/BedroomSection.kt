package com.anchor.ui.settings.sections

import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.PresenceSource
import com.anchor.data.settings.formatRoomList
import com.anchor.data.settings.parseRoomList
import com.anchor.ui.components.Hint
import com.anchor.ui.components.SettingRow

@Composable
fun BedroomSection(
    settings: AnchorSettings,
    onChange: ((AnchorSettings) -> AnchorSettings) -> Unit,
) {
    SettingsSection(title = "Bedroom block") {
        SettingRow(
            "Bedroom block",
            supporting = "Blocked apps don't open at all in these rooms during these hours, even after the " +
                "evening questions. Needs Home Assistant room detection; if it can't tell, apps open as usual.",
        ) {
            Switch(
                checked = settings.bedroomBlockEnabled,
                onCheckedChange = { on -> onChange { it.copy(bedroomBlockEnabled = on) } },
            )
        }
        if (settings.bedroomBlockEnabled) {
            if (settings.presenceSource == PresenceSource.VACATION) Hint("Off while vacation mode is on.")
            TextSetting("Rooms (comma-separated)", formatRoomList(settings.bedroomRooms), "Bedroom") { v ->
                onChange { it.copy(bedroomRooms = parseRoomList(v)) }
            }
            Hint("Matched against the tracker's name or state, case-insensitively.")
            TimeRow("Starts", settings.bedroomStartMinute) { m -> onChange { it.copy(bedroomStartMinute = m) } }
            TimeRow("Ends", settings.bedroomEndMinute) { m -> onChange { it.copy(bedroomEndMinute = m) } }
            Hint("Uses your blocked apps list.")
        }
    }
}
