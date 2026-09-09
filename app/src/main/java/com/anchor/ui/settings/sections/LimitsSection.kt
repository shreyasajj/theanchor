package com.anchor.ui.settings.sections

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.anchor.data.usage.AppLimit
import com.anchor.ui.components.Hint
import com.anchor.ui.components.SoftDivider
import com.anchor.ui.components.anchorTextFieldColors
import com.anchor.ui.settings.InstalledApp

/** One-line description of a configured limit, for the list row. */
object LimitSummary {
    fun describe(limit: AppLimit): String {
        val parts = buildList {
            limit.dailyMinutes?.let { add("$it min/day") }
            limit.dailyOpens?.let { add("$it opens") }
            limit.cooldownMinutes?.let { add("$it min cooldown") }
            limit.sessionMinutes?.let { add("$it min sessions") }
            if (limit.preOpenDelaySeconds > 0) add("${limit.preOpenDelaySeconds}s pause")
        }
        return if (parts.isEmpty()) "No limits set" else parts.joinToString(" · ")
    }
}

@Composable
fun LimitsSection(
    apps: List<InstalledApp>,
    limits: List<AppLimit>,
    onSetLimit: (String, (AppLimit) -> AppLimit) -> Unit,
    onClearLimit: (String) -> Unit,
) {
    var expandedPackage by remember { mutableStateOf<String?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    val labels = remember(apps) { apps.associate { it.packageName to it.label } }

    SettingsSection(title = "App limits") {
        Hint(
            "Limits apply all day, independently of the Evening Anchor. " +
                "Leave a field empty for no limit of that kind.",
            Modifier.padding(bottom = 6.dp),
        )

        limits.forEachIndexed { index, limit ->
            val expanded = expandedPackage == limit.packageName
            if (index > 0) SoftDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expandedPackage = if (expanded) null else limit.packageName }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(labels[limit.packageName] ?: limit.packageName, style = MaterialTheme.typography.bodyLarge)
                    Hint(LimitSummary.describe(limit))
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    LimitEditor(limit, onSetLimit)
                    TextButton(
                        onClick = {
                            onClearLimit(limit.packageName)
                            expandedPackage = null
                        },
                    ) { Text("Remove all limits", color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        TextButton(onClick = { pickerOpen = true }, modifier = Modifier.padding(top = 4.dp)) {
            Text("Add an app")
        }
    }

    if (pickerOpen) {
        val limited = limits.map { it.packageName }.toSet()
        AppPickerDialog(
            title = "Limit an app",
            apps = apps,
            selected = limited,
            onToggle = { packageName ->
                if (packageName in limited) {
                    onClearLimit(packageName)
                } else {
                    onSetLimit(packageName) { it }
                    expandedPackage = packageName
                    pickerOpen = false
                }
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun LimitEditor(
    limit: AppLimit,
    onSetLimit: (String, (AppLimit) -> AppLimit) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NumberField("Minutes / day", limit.dailyMinutes, Modifier.weight(1f)) { v ->
            onSetLimit(limit.packageName) { it.copy(dailyMinutes = v) }
        }
        NumberField("Opens / day", limit.dailyOpens, Modifier.weight(1f)) { v ->
            onSetLimit(limit.packageName) { it.copy(dailyOpens = v) }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NumberField("Cooldown, min", limit.cooldownMinutes, Modifier.weight(1f)) { v ->
            onSetLimit(limit.packageName) { it.copy(cooldownMinutes = v) }
        }
        NumberField("Max session, min", limit.sessionMinutes, Modifier.weight(1f)) { v ->
            onSetLimit(limit.packageName) { it.copy(sessionMinutes = v) }
        }
    }
    NumberField(
        "Pause before opening, seconds",
        limit.preOpenDelaySeconds.takeIf { it > 0 },
        Modifier.fillMaxWidth(),
    ) { v -> onSetLimit(limit.packageName) { it.copy(preOpenDelaySeconds = v ?: 0) } }
}

/** Empty means "no limit", so the value is nullable all the way down. */
@Composable
private fun NumberField(
    label: String,
    value: Int?,
    modifier: Modifier = Modifier,
    onChange: (Int?) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val cleaned = raw.filter { it.isDigit() }.take(4)
            text = cleaned
            onChange(cleaned.toIntOrNull())
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = anchorTextFieldColors(),
        shape = MaterialTheme.shapes.small,
        modifier = modifier.padding(vertical = 6.dp),
    )
}
