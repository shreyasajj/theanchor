package com.anchor.ui.lock

import android.content.Context
import android.content.Intent
import android.content.Intent as AndroidIntent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.anchor.domain.EveningDecision
import com.anchor.domain.LimitDecision
import com.anchor.data.settings.SettingsRepository
import com.anchor.domain.LimitGate
import com.anchor.domain.PauseLedger
import com.anchor.ui.components.Eyebrow
import com.anchor.ui.components.Hint
import com.anchor.ui.theme.AnchorTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

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
 * The wait before a restricted app opens. Three ways out: sit through it and
 * continue, walk away and close the app, or sit and breathe instead.
 *
 * Leaving the screen without finishing counts as abandoned, so coming back to
 * the app starts a fresh countdown rather than waving you through.
 */
@AndroidEntryPoint
class PauseActivity : ComponentActivity() {

    @Inject lateinit var limitGate: LimitGate
    @Inject lateinit var settingsRepository: SettingsRepository

    private var completed = false
    private lateinit var blockedPackage: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val totalSeconds = intent.getIntExtra(EXTRA_SECONDS, SimpleDelayTimer.DEFAULT_SECONDS)
        blockedPackage = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE).orEmpty()
        PauseLedger.begin(blockedPackage)
        // Durably, so a process restart does not forget the debt.
        lifecycleScope.launch { settingsRepository.owePause(blockedPackage) }
        val appLabel = blockedPackage.takeIf { it.isNotEmpty() }?.let { labelFor(it) }

        setContent {
            AnchorTheme {
                val start = remember { SystemClock.elapsedRealtime() }
                var remaining by remember { mutableIntStateOf(totalSeconds) }
                var budget by remember { mutableStateOf(emptyList<String>()) }

                LaunchedEffect(Unit) {
                    if (blockedPackage.isNotEmpty()) budget = limitGate.budgetFor(blockedPackage)
                }

                LaunchedEffect(Unit) {
                    while (remaining > 0) {
                        delay(200)
                        remaining = SimpleDelayTimer.remaining(
                            elapsedMillis = SystemClock.elapsedRealtime() - start,
                            totalSeconds = totalSeconds,
                        )
                    }
                }

                BackHandler(enabled = true) { closeApp() }

                Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(28.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Eyebrow(if (appLabel != null) "Opening $appLabel" else "A moment")
                        Spacer(Modifier.height(20.dp))
                        Text(
                            text = if (remaining > 0) "$remaining" else "Go",
                            style = MaterialTheme.typography.displayLarge,
                            color = if (remaining > 0) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (remaining > 0) "Breathe. Is this what you want to do right now?"
                            else "Go ahead, if you still want to.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )

                        if (budget.isNotEmpty()) {
                            Spacer(Modifier.height(24.dp))
                            budget.forEach { line ->
                                Hint(line, Modifier.padding(vertical = 2.dp))
                            }
                        }

                        Spacer(Modifier.height(40.dp))

                        AnimatedVisibility(visible = remaining == 0, enter = fadeIn()) {
                            Button(
                                onClick = { continueToApp() },
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                shape = MaterialTheme.shapes.small,
                            ) { Text("Continue", style = MaterialTheme.typography.titleMedium) }
                        }

                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { meditateInstead() },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = MaterialTheme.shapes.small,
                        ) { Text("Breathe for a minute instead", style = MaterialTheme.typography.titleMedium) }

                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            TextButton(onClick = { closeApp() }) {
                                Text(if (appLabel != null) "Close $appLabel" else "Close the app")
                            }
                        }
                    }
                }
            }
        }
    }

    /** The wait was served: let the app through. */
    private fun continueToApp() {
        completed = true
        PauseLedger.complete(blockedPackage, System.currentTimeMillis())
        lifecycleScope.launch { settingsRepository.settlePause(blockedPackage) }
        finish()
    }

    /** Chose not to go in at all. Nothing is credited; the app stays closed. */
    private fun closeApp() {
        completed = true
        PauseLedger.abandon(blockedPackage)
        goHome()
        finish()
    }

    private fun meditateInstead() {
        completed = true   // the meditation screen owns the outcome from here
        startActivity(MeditationActivity.intent(this, blockedPackage))
        finish()
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * This activity is singleInstance, so relaunching it while the old
     * instance is merely stopped delivers a new intent instead of creating it
     * afresh. Without recreating, the countdown would carry on from wherever
     * it was left, which after walking away is almost always zero: the wait
     * would be over before it began. Start the whole screen again.
     */
    override fun onNewIntent(intent: AndroidIntent) {
        super.onNewIntent(intent)
        setIntent(intent)
        completed = true    // this instance is being replaced, not abandoned
        recreate()
    }

    /**
     * Pressing home stops this screen without destroying it, so onDestroy is
     * far too late to notice someone walking away mid-countdown. onStop is the
     * moment the pause stopped being watched.
     */
    override fun onStop() {
        if (!completed) PauseLedger.abandon(blockedPackage)
        super.onStop()
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
