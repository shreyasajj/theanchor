package com.anchor.ui.settings.sections

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anchor.data.db.Phase
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.LocationMode
import com.anchor.data.settings.formatRoomList
import com.anchor.ui.components.Hint
import com.anchor.ui.components.SettingRow

@Composable
fun HomeAssistantSection(
    settings: AnchorSettings,
    onChange: ((AnchorSettings) -> AnchorSettings) -> Unit,
    onSetRooms: (Phase, String) -> Unit,
    onSetLocationMode: (Phase, LocationMode) -> Unit,
) {
    SettingsSection(title = "Home Assistant") {
        TextSetting("Base URL", settings.haBaseUrl, "http://192.168.1.10:8123") { v ->
            onChange { it.copy(haBaseUrl = v.trim()) }
        }
        TextSetting("Long-lived access token", settings.haToken, secret = true) { v ->
            onChange { it.copy(haToken = v.trim()) }
        }
        TextSetting(
            "Device tracker entity", settings.haDeviceTrackerEntityId,
            "device_tracker.pixel",
        ) { v -> onChange { it.copy(haDeviceTrackerEntityId = v.trim()) } }

        SettingRow(
            "Ask even without Home Assistant",
            supporting = "Off: when Home Assistant is not set up or cannot be reached, the morning lock " +
                "is skipped and the evening shows a 5-second pause. On: the questions are asked anyway. " +
                "A confirmed \"not home\" always skips.",
        ) {
            Switch(
                checked = settings.enforceWithoutHomeAssistant,
                onCheckedChange = { on -> onChange { it.copy(enforceWithoutHomeAssistant = on) } },
            )
        }
    }

    SettingsSection(title = "Morning location") {
        LocationModePicker(settings.morningLocationMode) { onSetLocationMode(Phase.MORNING, it) }
        if (settings.morningLocationMode == LocationMode.SPECIFIC_ROOMS) {
            TextSetting(
                "Allowed rooms (comma-separated)",
                formatRoomList(settings.morningAllowedRooms),
                "Bedroom, Office",
            ) { onSetRooms(Phase.MORNING, it) }
            Hint("Matched against the tracker's friendly name, case-insensitively.")
        }
    }

    SettingsSection(title = "Evening location") {
        LocationModePicker(settings.eveningLocationMode) { onSetLocationMode(Phase.EVENING, it) }
        if (settings.eveningLocationMode == LocationMode.SPECIFIC_ROOMS) {
            TextSetting(
                "Restricted rooms (comma-separated)",
                formatRoomList(settings.eveningAllowedRooms),
                "Bedroom, Living Room",
            ) { onSetRooms(Phase.EVENING, it) }
        }
    }

    SettingsSection(title = "Remote kill switch") {
        SettingRow(
            "Enabled",
            supporting = "Checked at the moment of every block. Disabling the app means opening " +
                "Home Assistant; that friction is the point.",
        ) {
            Switch(
                checked = settings.killSwitchEnabled,
                onCheckedChange = { on -> onChange { it.copy(killSwitchEnabled = on) } },
            )
        }
        if (settings.killSwitchEnabled) {
            TextSetting(
                "Entity ID", settings.killSwitchEntityId, "input_boolean.anchor_override",
            ) { v -> onChange { it.copy(killSwitchEntityId = v.trim()) } }
            TextSetting("State that disables blocking", settings.killSwitchOverrideState, "on") { v ->
                onChange { it.copy(killSwitchOverrideState = v.trim()) }
            }
            SettingRow(
                "Treat an outage as override",
                supporting = "Off: an unreachable Home Assistant leaves blocking in place. " +
                    "On: losing network access disables all blocking, a one-gesture bypass, " +
                    "so turn this on deliberately.",
            ) {
                Switch(
                    checked = settings.killSwitchFailOpenOnOutage,
                    onCheckedChange = { on -> onChange { it.copy(killSwitchFailOpenOnOutage = on) } },
                )
            }
        }
    }
}

@Composable
private fun LocationModePicker(current: LocationMode, onPick: (LocationMode) -> Unit) {
    RadioGroup(
        options = LocationMode.entries,
        selected = current,
        label = {
            when (it) {
                LocationMode.AT_HOME -> "At home"
                LocationMode.SPECIFIC_ROOMS -> "Specific rooms"
            }
        },
        supporting = {
            when (it) {
                LocationMode.AT_HOME -> "Anywhere the tracker reports \"home\"."
                LocationMode.SPECIFIC_ROOMS -> "Only when the tracker's name contains one of your rooms."
            }
        },
        onPick = onPick,
    )
}
