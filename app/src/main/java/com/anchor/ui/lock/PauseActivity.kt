package com.anchor.ui.lock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.anchor.domain.EveningDecision
import com.anchor.domain.LimitDecision
import com.anchor.ui.components.Eyebrow
import com.anchor.ui.theme.AnchorTheme
import kotlinx.coroutines.delay

/** Pure countdown arithmetic, so the timing is unit-testable. */
object SimpleDelayTimer {
    const val DEFAULT_SECONDS = 5

    /** Seconds still to wait, rounded up, floored at zero. */
    fun remaining(elapsedMillis: Long, totalSeconds: Int): Int {
        val remainingMillis = totalSeconds * 1000L - elapsedMillis
        if (remainingMillis <= 0) return 0
        return ((remainingMillis + 999) / 1000).toInt()
    }
}

/**
 * Decides how long the single pause screen lasts when the evening gate and a
 * per-app pre-open pause both want one. They are never shown back to back.
 */
object PauseCoalescing {
    const val EVENING_DELAY_SECONDS = SimpleDelayTimer.DEFAULT_SECONDS

    fun secondsFor(evening: EveningDecision, limit: LimitDecision): Int {
        val eveningSeconds =
            if (evening is EveningDecision.SimpleDelay) EVENING_DELAY_SECONDS else 0
        val limitSeconds = (limit as? LimitDecision.Pause)?.seconds ?: 0
        return maxOf(eveningSeconds, limitSeconds)
    }
}

/**
 * A blank screen that simply makes you wait. Used both for the evening
 * fail-open path (5s) and for a per-app pre-open pause (any length).
 * A pause, not a wall.
 */
class PauseActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val totalSeconds = intent.getIntExtra(EXTRA_SECONDS, SimpleDelayTimer.DEFAULT_SECONDS)
        val appLabel = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE)?.let { labelFor(it) }

        setContent {
            AnchorTheme {
                val start = remember { SystemClock.elapsedRealtime() }
                var remaining by remember { mutableIntStateOf(totalSeconds) }

                LaunchedEffect(Unit) {
                    while (remaining > 0) {
                        delay(200)
                        remaining = SimpleDelayTimer.remaining(
                            elapsedMillis = SystemClock.elapsedRealtime() - start,
                            totalSeconds = totalSeconds,
                        )
                    }
                }

                BackHandler(enabled = remaining > 0) { }

                Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(28.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Eyebrow(if (appLabel != null) "Opening $appLabel" else "A moment")
                        Spacer(Modifier.height(24.dp))
                        Text(
                            text = if (remaining > 0) "$remaining" else "Go",
                            style = MaterialTheme.typography.displayLarge,
                            color = if (remaining > 0) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = if (remaining > 0) "Breathe. Is this what you want to do right now?"
                            else "Go ahead, if you still want to.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(48.dp))
                        AnimatedVisibility(visible = remaining == 0, enter = fadeIn()) {
                            Button(
                                onClick = { finish() },
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                shape = MaterialTheme.shapes.small,
                            ) { Text("Continue", style = MaterialTheme.typography.titleMedium) }
                        }
                    }
                }
            }
        }
    }

    private fun labelFor(packageName: String): String? = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()

    companion object {
        const val EXTRA_SECONDS = "com.anchor.extra.PAUSE_SECONDS"
        const val EXTRA_BLOCKED_PACKAGE = "com.anchor.extra.BLOCKED_PACKAGE"

        fun intent(context: Context, seconds: Int, blockedPackage: String): Intent =
            Intent(context, PauseActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(EXTRA_SECONDS, seconds)
                putExtra(EXTRA_BLOCKED_PACKAGE, blockedPackage)
            }
    }
}
