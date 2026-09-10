package com.anchor.ui.lock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.lifecycleScope
import com.anchor.data.settings.SettingsRepository
import com.anchor.data.usage.MeditationSession
import com.anchor.data.usage.MeditationSessionDao
import com.anchor.domain.AnchorDate
import com.anchor.domain.BreathPattern
import com.anchor.domain.BreathingGuide
import com.anchor.domain.PauseLedger
import com.anchor.ui.components.Eyebrow
import com.anchor.ui.components.Hint
import com.anchor.ui.theme.AnchorTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A guided breath instead of opening the app. Pick a length, follow the
 * circle, and at the end choose whether you still want to go in.
 *
 * Finishing counts as serving the pause, so "Open anyway" is allowed
 * afterwards. That is deliberate: the point is the pause, not the denial.
 */
@AndroidEntryPoint
class MeditationActivity : ComponentActivity() {

    @Inject lateinit var sessionDao: MeditationSessionDao
    @Inject lateinit var anchorDate: AnchorDate
    @Inject lateinit var settingsRepository: SettingsRepository

    private var blockedPackage: String? = null

    /** Set once the user has chosen what happens next, so onStop can tell
     *  a deliberate exit from simply walking away. */
    private var resolved = false

    private enum class Stage { CHOOSE, BREATHING, DONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        blockedPackage = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE)?.takeIf { it.isNotEmpty() }
        val appLabel = blockedPackage?.let { labelFor(it) }

        setContent {
            AnchorTheme {
                var stage by remember { mutableStateOf(Stage.CHOOSE) }
                var minutes by remember { mutableStateOf(1) }
                var satSeconds by remember { mutableStateOf(0) }

                BackHandler(enabled = true) {
                    when (stage) {
                        Stage.CHOOSE -> leave()
                        // Stopping early still counts for what was actually sat.
                        Stage.BREATHING -> stage = Stage.DONE
                        Stage.DONE -> leave()
                    }
                }

                Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(28.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        when (stage) {
                            Stage.CHOOSE -> Choose(
                                appLabel = appLabel,
                                selected = minutes,
                                onSelect = { minutes = it },
                                onStart = { stage = Stage.BREATHING },
                                onSkip = { leave() },
                            )

                            Stage.BREATHING -> Breathing(
                                minutes = minutes,
                                onFinished = { sat ->
                                    satSeconds = sat
                                    record(sat)
                                    stage = Stage.DONE
                                },
                                onStopEarly = { sat ->
                                    satSeconds = sat
                                    if (sat >= MIN_RECORDED_SECONDS) record(sat)
                                    stage = Stage.DONE
                                },
                            )

                            Stage.DONE -> Done(
                                satSeconds = satSeconds,
                                appLabel = appLabel,
                                onOpenAnyway = { openAnyway() },
                                onDone = { leave() },
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Choose(
        appLabel: String?,
        selected: Int,
        onSelect: (Int) -> Unit,
        onStart: () -> Unit,
        onSkip: () -> Unit,
    ) {
        Eyebrow("Instead of this")
        Spacer(Modifier.height(14.dp))
        Text(
            text = appLabel?.let { "Sit for a minute rather than opening $it." }
                ?: "Sit for a minute.",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Hint("Breathe in for four, hold for two, out for six. Follow the circle.")
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
        TextButton(onClick = onSkip) { Text("Not now") }
    }

    @Composable
    private fun Breathing(
        minutes: Int,
        onFinished: (Int) -> Unit,
        onStopEarly: (Int) -> Unit,
    ) {
        val pattern = remember { BreathPattern() }
        val start = remember { SystemClock.elapsedRealtime() }
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
    private fun Done(
        satSeconds: Int,
        appLabel: String?,
        onOpenAnyway: () -> Unit,
        onDone: () -> Unit,
    ) {
        Eyebrow("Done")
        Spacer(Modifier.height(14.dp))
        Text(
            text = if (satSeconds >= MIN_RECORDED_SECONDS)
                "You sat for ${BreathingGuide.formatClock(satSeconds)}."
            else "No matter. You stopped, and that counts too.",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Hint(
            text = appLabel?.let { "You can still open $it if you want to." }
                ?: "Back to your day.",
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(36.dp))
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = MaterialTheme.shapes.small,
        ) { Text("I'm good", style = MaterialTheme.typography.titleMedium) }
        if (appLabel != null) {
            TextButton(onClick = onOpenAnyway) { Text("Open $appLabel anyway") }
        }
    }

    private fun record(seconds: Int) {
        val startedAt = anchorDate.nowMillis() - seconds * 1000L
        lifecycleScope.launch {
            sessionDao.insert(
                MeditationSession(
                    insteadOfPackage = blockedPackage,
                    startedAtMillis = startedAt,
                    seconds = seconds,
                )
            )
        }
    }

    /** Sitting served the pause, so going in now is allowed. */
    private fun openAnyway() {
        resolved = true
        blockedPackage?.let {
            PauseLedger.complete(it, System.currentTimeMillis())
            lifecycleScope.launch { settingsRepository.settlePause(it) }
        }
        finish()
    }

    /** Walking away mid-sit leaves the pause owed, like abandoning it. */
    override fun onStop() {
        if (!resolved) blockedPackage?.let { PauseLedger.abandon(it) }
        super.onStop()
    }

    /** Leave without opening the app. */
    private fun leave() {
        resolved = true
        blockedPackage?.let { PauseLedger.abandon(it) }
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    private fun labelFor(packageName: String): String? = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()

    companion object {
        const val EXTRA_BLOCKED_PACKAGE = "com.anchor.extra.BLOCKED_PACKAGE"

        /** Below this a sit is not worth recording. */
        const val MIN_RECORDED_SECONDS = 20

        fun intent(context: Context, blockedPackage: String? = null): Intent =
            Intent(context, MeditationActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                if (blockedPackage != null) putExtra(EXTRA_BLOCKED_PACKAGE, blockedPackage)
            }
    }
}
