package com.anchor.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** Small, tracked-out, uppercase eyebrow used above every group of content. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = modifier,
    )
}

/** The one card shape used everywhere: low surface, soft outline, generous padding. */
@Composable
fun AnchorCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/** A card with an eyebrow title. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnchorCard(modifier = modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(title, Modifier.weight(1f))
            trailing?.invoke()
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

/** A filled or hollow dot: done / pending. */
@Composable
fun StatusDot(done: Boolean, modifier: Modifier = Modifier, size: Int = 10) {
    val color = if (done) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
    Box(
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .then(
                if (done) Modifier.background(color)
                else Modifier.border(1.5.dp, color, CircleShape)
            )
    )
}

/** A thin horizontal meter; turns red when exhausted. */
@Composable
fun Meter(fraction: Float, exhausted: Boolean, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val fill = if (exhausted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(track)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(CircleShape)
                .background(fill)
        )
    }
}

/** A small rounded chip for status words ("Done", "Off", "Unreachable"). */
@Composable
fun Pill(text: String, tone: PillTone = PillTone.NEUTRAL) {
    val (bg, fg) = when (tone) {
        PillTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
        PillTone.ACCENT -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        PillTone.GOOD -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        PillTone.BAD -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Box(
        Modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

enum class PillTone { NEUTRAL, ACCENT, GOOD, BAD }

@Composable
fun SoftDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, color = MaterialTheme.colorScheme.outlineVariant)
}

/** Body copy in the muted colour: explanations under controls. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
fun anchorTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
)

/**
 * A text field whose displayed text is owned locally and pushed outward on
 * every change. Settings are persisted asynchronously, so binding the field
 * directly to the stored value would make the cursor jump and drop
 * characters; this keeps typing smooth while still committing each edit.
 * External changes are adopted only while the field is not focused.
 */
@Composable
fun DraftTextField(
    value: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    var text by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) { if (!focused && text != value) text = value }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onCommit(it) },
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it, color = MaterialTheme.colorScheme.outline) } },
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        colors = anchorTextFieldColors(),
        shape = MaterialTheme.shapes.small,
    )
}

/** Label on the left, value or control on the right. */
@Composable
fun SettingRow(
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) Hint(supporting)
        }
        trailing()
    }
}
