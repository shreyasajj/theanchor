package com.anchor.ui.lock

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.anchor.domain.BreathPattern
import com.anchor.domain.BreathingGuide
import com.anchor.ui.components.Eyebrow
import com.anchor.ui.components.Hint

/**
 * The three stages of a guided sit, shared by the optional breathing screen
 * and the evening sit lockdown. Each is a plain column of content; the
 * caller supplies the scaffold.
 */

@Composable
fun ChooseSit(
    eyebrow: String,
    title: String,
    selected: Int,
    onSelect: (Int) -> Unit,
    onStart: () -> Unit,
    skipLabel: String? = null,
    onSkip: () -> Unit = {},
    hint: String = "Breathe in for four, hold for two, out for six. Follow the circle.",
) {
    Eyebrow(eyebrow)
    Spacer(Modifier.height(14.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(10.dp))
    Hint(hint)
    Spacer(Modifier.height(32.dp))

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        BreathingGuide.DURATION_CHOICES_MINUTES.forEach { choice ->
            val active = choice == selected
            if (active) {
                Button(
                    onClick = { onSelect(choice) },
                    shape = MaterialTheme.shapes.small,
                ) { Text("$choice min") }
            } else {
                OutlinedButton(
                    onClick = { onSelect(choice) },
                    shape = MaterialTheme.shapes.small,
                ) { Text("$choice min") }
            }
        }
    }

    Spacer(Modifier.height(32.dp))
    Button(
        onClick = onStart,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = MaterialTheme.shapes.small,
    ) { Text("Begin", style = MaterialTheme.typography.titleMedium) }
    if (skipLabel != null) TextButton(onClick = onSkip) { Text(skipLabel) }
}

@Composable
fun BreathingSit(
    minutes: Int,
    onFinished: (Int) -> Unit,
    onStopEarly: (Int) -> Unit,
) {
    val pattern = remember { BreathPattern() }
    // Saved so a recreate mid-sit does not restart the breath from zero.
    val start = rememberSaveable { SystemClock.elapsedRealtime() }
    var elapsed by remember { mutableLongStateOf(0L) }
    val haptics = LocalHapticFeedback.current

    // Frame-synced so the circle moves smoothly without a busy loop.
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { }
            elapsed = SystemClock.elapsedRealtime() - start
            if (BreathingGuide.remainingSeconds(elapsed, minutes) == 0) {
                onFinished(minutes * 60)
                return@LaunchedEffect
            }
        }
    }

    val state = BreathingGuide.stateAt(elapsed, pattern)

    // A nudge at each change of phase, so this works with eyes closed.
    LaunchedEffect(state.phase) {
        if (elapsed > 0) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Text(
        text = BreathingGuide.formatClock(BreathingGuide.remainingSeconds(elapsed, minutes)),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.outline,
    )
    Spacer(Modifier.height(28.dp))

    Box(
        Modifier.fillMaxWidth().aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        val ring = MaterialTheme.colorScheme.outlineVariant
        val fill = MaterialTheme.colorScheme.primary
        Canvas(Modifier.fillMaxSize()) {
            val full = size.minDimension / 2f
            val centre = Offset(size.width / 2f, size.height / 2f)
            // The outer ring is the full breath; the disc is where you are.
            drawCircle(color = ring, radius = full, center = centre, style = Stroke(width = 2f))
            val low = full * 0.34f
            drawCircle(
                color = fill.copy(alpha = 0.16f),
                radius = low + (full - low) * state.openness,
                center = centre,
            )
            drawCircle(
                color = fill,
                radius = low + (full - low) * state.openness,
                center = centre,
                style = Stroke(width = 3f),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = BreathingGuide.label(state.phase),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${state.secondsLeftInPhase}",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(28.dp))
    TextButton(onClick = { onStopEarly((elapsed / 1000L).toInt()) }) { Text("That's enough") }
}

@Composable
fun SitDone(
    satSeconds: Int,
    hint: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
) {
    Eyebrow("Done")
    Spacer(Modifier.height(14.dp))
    Text(
        text = if (satSeconds >= MeditationActivity.MIN_RECORDED_SECONDS)
            "You sat for ${BreathingGuide.formatClock(satSeconds)}."
        else "No matter. You stopped, and that counts too.",
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(10.dp))
    Hint(text = hint, modifier = Modifier.padding(horizontal = 8.dp))
    Spacer(Modifier.height(36.dp))
    Button(
        onClick = onPrimary,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = MaterialTheme.shapes.small,
    ) { Text(primaryLabel, style = MaterialTheme.typography.titleMedium) }
    if (secondaryLabel != null) {
        TextButton(onClick = onSecondary) { Text(secondaryLabel) }
    }
}
