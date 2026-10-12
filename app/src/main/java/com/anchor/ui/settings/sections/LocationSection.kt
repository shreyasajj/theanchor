package com.anchor.ui.settings.sections

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anchor.data.location.SavedPlace
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.LocationSource
import com.anchor.ui.components.Hint
import com.anchor.ui.components.SettingRow
import com.anchor.ui.settings.PlaceTarget
import java.util.Locale

@Composable
fun LocationSection(
    settings: AnchorSettings,
    placeMessage: String?,
    onChange: ((AnchorSettings) -> AnchorSettings) -> Unit,
    onCapturePlace: (PlaceTarget) -> Unit,
    onSetRadius: (PlaceTarget, String) -> Unit,
) {
    SettingsSection(title = "Where you are") {
        RadioGroup(
            options = LocationSource.entries,
            selected = settings.locationSource,
            label = {
                when (it) {
                    LocationSource.HOME_ASSISTANT -> "Home Assistant"
                    LocationSource.PHONE -> "This phone"
                }
            },
            supporting = {
                when (it) {
                    LocationSource.HOME_ASSISTANT -> "Ask your device tracker, using the morning and evening settings below."
                    LocationSource.PHONE -> "Compare this phone's location with a saved home place. " +
                        "Needs location set to \"Allow all the time\"."
                }
            },
            onPick = { source -> onChange { it.copy(locationSource = source) } },
        )
        if (settings.locationSource == LocationSource.PHONE) {
            PlaceSetting("Home place", settings.homePlace, PlaceTarget.HOME, onCapturePlace, onSetRadius)
        }

        SettingRow(
            "Vacation mode",
            supporting = "Treat a second place as home, using this phone's location. " +
                "Home Assistant is not asked where you are while it is on.",
        ) {
            Switch(
                checked = settings.vacationMode,
                onCheckedChange = { on -> onChange { it.copy(vacationMode = on) } },
            )
        }
        if (settings.vacationMode) {
            PlaceSetting("Vacation place", settings.vacationPlace, PlaceTarget.VACATION, onCapturePlace, onSetRadius)
            if (settings.vacationPlace == null) Hint("Nothing changes until a vacation place is saved.")
        }

        placeMessage?.let { Hint(it, Modifier.padding(vertical = 6.dp)) }

        SettingRow(
            "Ask even when location is unknown",
            supporting = "Off: when your location cannot be read (Home Assistant down or not set up, " +
                "or no phone location), the morning lock is skipped and the evening shows a 5-second " +
                "pause. On: the questions are asked anyway. A confirmed \"away\" always skips.",
        ) {
            Switch(
                checked = settings.enforceWithoutHomeAssistant,
                onCheckedChange = { on -> onChange { it.copy(enforceWithoutHomeAssistant = on) } },
            )
        }
    }
}

@Composable
private fun PlaceSetting(
    label: String,
    place: SavedPlace?,
    target: PlaceTarget,
    onCapture: (PlaceTarget) -> Unit,
    onSetRadius: (PlaceTarget, String) -> Unit,
) {
    SettingRow(
        label,
        supporting = place?.let { "%.5f, %.5f".format(Locale.US, it.latitude, it.longitude) } ?: "Not set",
    ) {
        TextButton(onClick = { onCapture(target) }) { Text("Use current location") }
    }
    if (place != null) {
        TextSetting("Radius in metres (25–5000)", place.radiusMeters.toString(), "150") {
            onSetRadius(target, it)
        }
    }
}
