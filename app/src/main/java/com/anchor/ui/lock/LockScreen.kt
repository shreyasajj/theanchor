package com.anchor.ui.lock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.anchor.ui.components.Eyebrow
import com.anchor.ui.components.anchorTextFieldColors

/**
 * The shared blocking UI: an eyebrow, a title, the dynamically rendered
 * questions, and a single submit button. Back is swallowed: the only way out
 * is to answer.
 */
@Composable
fun LockScreen(
    eyebrow: String,
    title: String,
    subtitle: String,
    state: LockUiState,
    onAnswerChanged: (String, String) -> Unit,
    onSubmit: () -> Unit,
) {
    // Consumes the back gesture without doing anything.
    BackHandler(enabled = true) { }

    val focus = LocalFocusManager.current

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp),
            ) {
                Spacer(Modifier.height(56.dp))
                Eyebrow(eyebrow)
                Spacer(Modifier.height(12.dp))
                Text(text = title, style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(44.dp))

                state.questions.forEachIndexed { index, question ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = "%02d".format(index + 1),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 6.dp, end = 14.dp),
                        )
                        Text(
                            text = question.prompt,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    val last = index == state.questions.lastIndex
                    OutlinedTextField(
                        value = state.answers[question.slotKey].orEmpty(),
                        onValueChange = { onAnswerChanged(question.slotKey, it) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = anchorTextFieldColors(),
                        shape = MaterialTheme.shapes.small,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = if (last) ImeAction.Done else ImeAction.Next,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { focus.clearFocus(); if (state.canSubmit) onSubmit() },
                        ),
                    )
                    Spacer(Modifier.height(32.dp))
                }
                Spacer(Modifier.height(8.dp))
            }

            // Footer pinned above the keyboard.
            Column(Modifier.padding(horizontal = 28.dp, vertical = 20.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (state.questions.isEmpty()) "" else
                            "${state.answeredCount} of ${state.questions.size} answered",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.exportWarning?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                Button(
                    onClick = onSubmit,
                    enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContentColor = MaterialTheme.colorScheme.outline,
                    ),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            if (state.isSubmitting) "Saving…" else "Continue",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }
}
