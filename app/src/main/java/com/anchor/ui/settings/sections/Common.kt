package com.anchor.ui.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.anchor.ui.components.DraftTextField
import com.anchor.ui.components.Hint
import com.anchor.ui.components.SectionCard
import com.anchor.ui.components.anchorTextFieldColors
import com.anchor.ui.settings.InstalledApp

@Composable
fun SettingsSection(title: String, content: @Composable () -> Unit) {
    SectionCard(title = title, modifier = Modifier.padding(vertical = 6.dp)) { content() }
}

@Composable
fun TextSetting(
    label: String,
    value: String,
    placeholder: String = "",
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    DraftTextField(
        value = value,
        onCommit = onChange,
        label = label,
        placeholder = placeholder.takeIf { it.isNotEmpty() },
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}

@Composable
fun AnchorDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    title: String? = null,
    confirmLabel: String = "Done",
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = title?.let { { Text(it, style = MaterialTheme.typography.titleLarge) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { content() },
    )
}

/** One radio row per option. */
@Composable
fun <T> RadioGroup(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    supporting: ((T) -> String?)? = null,
    onPick: (T) -> Unit,
) {
    options.forEach { option ->
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected == option, onClick = { onPick(option) })
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected == option, onClick = { onPick(option) })
            Column(Modifier.padding(start = 6.dp)) {
                Text(label(option), style = MaterialTheme.typography.bodyLarge)
                supporting?.invoke(option)?.let { Hint(it) }
            }
        }
    }
}

/**
 * A searchable, multi-select list of installed apps, shown in a dialog so the
 * settings page itself stays short. Bounded height keeps the LazyColumn legal
 * inside the dialog.
 */
@Composable
fun AppPickerDialog(
    title: String,
    apps: List<InstalledApp>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search apps", color = MaterialTheme.colorScheme.outline) },
                    singleLine = true,
                    colors = anchorTextFieldColors(),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.width(0.dp).padding(top = 8.dp))
                if (apps.isEmpty()) {
                    Hint("Loading installed apps…", Modifier.padding(vertical = 16.dp))
                }
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(shown, key = { it.packageName }) { app ->
                        val checked = app.packageName in selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(app.packageName) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { onToggle(app.packageName) })
                            Column(Modifier.padding(start = 4.dp)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}
