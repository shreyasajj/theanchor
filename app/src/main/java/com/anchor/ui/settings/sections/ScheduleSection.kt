package com.anchor.ui.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anchor.data.settings.AnchorSettings
import com.anchor.ui.components.Hint
import com.anchor.ui.components.SoftDivider

fun formatMinute(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

/** Morning start/end, evening start/end, and the usage-limit day reset. */
@Composable
fun ScheduleSection(
    settings: AnchorSettings,
    onChange: ((AnchorSettings) -> AnchorSettings) -> Unit,
) {
    SettingsSection(title = "Schedule") {
        TimeRow("Morning starts", settings.morningStartMinute) { m ->
            onChange { it.copy(morningStartMinute = m) }
        }
        TimeRow("Morning ends", settings.morningEndMinute) { m ->
            onChange { it.copy(morningEndMinute = m) }
        }
        SoftDivider(Modifier.padding(vertical = 4.dp))
        TimeRow("Evening starts", settings.eveningStartMinute) { m ->
            onChange { it.copy(eveningStartMinute = m) }
        }
        TimeRow("Evening ends", settings.eveningEndMinute) { m ->
            onChange { it.copy(eveningEndMinute = m) }
        }
        Hint(
            "The evening window may cross midnight: 20:00 to 05:00 is one night.",
            Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        SoftDivider(Modifier.padding(vertical = 4.dp))
        TimeRow("Usage limits reset at", settings.dayResetMinute) { m ->
            onChange { it.copy(dayResetMinute = m) }
        }
        Hint("Not midnight by default, so a late night does not eat tomorrow's budget.")
    }
}

@Composable
fun TimeRow(label: String, minuteOfDay: Int, onPicked: (Int) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showPicker = true }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            formatMinute(minuteOfDay),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleMedium,
        )
    }

    if (showPicker) {
        val state = rememberTimePickerState(
            initialHour = minuteOfDay / 60,
            initialMinute = minuteOfDay % 60,
            is24Hour = true,
        )
        AnchorDialog(
            title = label,
            confirmLabel = "Set",
            onDismiss = { showPicker = false },
            onConfirm = {
                onPicked(state.hour * 60 + state.minute)
                showPicker = false
            },
        ) {
            Column { TimePicker(state = state) }
        }
    }
}
