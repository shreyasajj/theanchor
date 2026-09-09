package com.anchor.ui.settings.sections

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anchor.data.export.NoteFormat
import com.anchor.data.settings.AnchorSettings
import com.anchor.ui.components.Hint
import com.anchor.ui.components.Pill
import com.anchor.ui.components.PillTone
import com.anchor.ui.components.SettingRow

@Composable
fun ExportSection(
    settings: AnchorSettings,
    onPickFolder: () -> Unit,
    onChange: ((AnchorSettings) -> AnchorSettings) -> Unit,
) {
    SettingsSection(title = "Markdown export") {
        SettingRow(
            "Export folder",
            supporting = "One file per day, named YYYY-MM-DD.md.",
        ) {
            if (settings.exportTreeUri == null) Pill("Not set", PillTone.BAD) else Pill("Chosen", PillTone.GOOD)
        }
        TextButton(onClick = onPickFolder) {
            Text(if (settings.exportTreeUri == null) "Choose folder" else "Change folder")
        }

        Hint("Format", Modifier.padding(top = 12.dp, bottom = 4.dp))
        RadioGroup(
            options = NoteFormat.entries,
            selected = settings.noteFormat,
            label = {
                when (it) {
                    NoteFormat.PLAIN -> "Plain Markdown"
                    NoteFormat.OBSIDIAN -> "Obsidian"
                }
            },
            supporting = {
                when (it) {
                    NoteFormat.PLAIN -> "Exactly the spec's format."
                    NoteFormat.OBSIDIAN -> "Adds YAML frontmatter and a link to the previous day."
                }
            },
            onPick = { format -> onChange { it.copy(noteFormat = format) } },
        )
        Hint(
            "For Obsidian, point the folder at your vault's daily notes. " +
                "For Standard Notes, use plain Markdown and its folder importer: " +
                "its sync is end-to-end encrypted with no local API to write to.",
            Modifier.padding(top = 8.dp),
        )
    }

    SettingsSection(title = "Joplin (optional)") {
        TextSetting("API URL", settings.joplinBaseUrl, "http://192.168.1.10:41184") { v ->
            onChange { it.copy(joplinBaseUrl = v.trim()) }
        }
        TextSetting("API token", settings.joplinToken, secret = true) { v ->
            onChange { it.copy(joplinToken = v.trim()) }
        }
        Hint("If a push fails, The Anchor falls back to the local file silently.")
    }
}
