package com.anchor.ui.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.anchor.data.db.CustomQuestion
import com.anchor.ui.components.DraftTextField
import com.anchor.ui.components.Hint
import com.anchor.ui.components.anchorTextFieldColors

/**
 * Add, edit, reorder and delete the questions for one phase. Editing a
 * question keeps its slot key, so historical answers stay attached.
 */
@Composable
fun QuestionsSection(
    title: String,
    description: String,
    questions: List<CustomQuestion>,
    onAdd: (String) -> Unit,
    onEdit: (CustomQuestion, String) -> Unit,
    onDelete: (CustomQuestion) -> Unit,
    onMove: (CustomQuestion, Int) -> Unit,
) {
    var draft by remember { mutableStateOf("") }

    SettingsSection(title = title) {
        Hint(description, Modifier.padding(bottom = 6.dp))

        questions.forEachIndexed { index, question ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "%02d".format(index + 1),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(end = 10.dp),
                )
                // Keyed on id so a reorder re-seeds the local draft correctly.
                androidx.compose.runtime.key(question.id) {
                    DraftTextField(
                        value = question.prompt,
                        onCommit = { onEdit(question, it) },
                        singleLine = false,
                        modifier = Modifier.weight(1f),
                    )
                }
                Column {
                    IconButton(onClick = { onMove(question, -1) }, enabled = index > 0) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Move up")
                    }
                    IconButton(onClick = { onMove(question, 1) }, enabled = index < questions.lastIndex) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = "Move down")
                    }
                }
                IconButton(onClick = { onDelete(question) }) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("New question") },
                modifier = Modifier.weight(1f),
                colors = anchorTextFieldColors(),
                shape = MaterialTheme.shapes.small,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAdd(draft); draft = "" }),
            )
            TextButton(
                onClick = { onAdd(draft); draft = "" },
                enabled = draft.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Add") }
        }
    }
}
