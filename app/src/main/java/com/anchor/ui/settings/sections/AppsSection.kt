package com.anchor.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anchor.ui.components.Hint
import com.anchor.ui.settings.InstalledApp

/**
 * Used twice: once for the evening blocked list, once for the morning
 * allowlist. Selected apps show as removable chips; a dialog picks more.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppsSection(
    title: String,
    description: String,
    apps: List<InstalledApp>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val labels = remember(apps) { apps.associate { it.packageName to it.label } }

    SettingsSection(title = title) {
        Hint(description, Modifier.padding(bottom = 10.dp))

        if (selected.isEmpty()) {
            Text(
                "None selected",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.outline,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                selected.sortedBy { labels[it]?.lowercase() ?: it }.forEach { pkg ->
                    AssistChip(
                        onClick = { onToggle(pkg) },
                        label = { Text(labels[pkg] ?: pkg) },
                        trailingIcon = {
                            Icon(Icons.Default.Close, contentDescription = "Remove", Modifier.padding(0.dp))
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                            trailingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        border = null,
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { pickerOpen = true }) { Text("Choose apps") }
        }
    }

    if (pickerOpen) {
        AppPickerDialog(
            title = title,
            apps = apps,
            selected = selected,
            onToggle = onToggle,
            onDismiss = { pickerOpen = false },
        )
    }
}
