package com.anchor.ui.lock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.anchor.data.settings.SettingsProvider
import com.anchor.data.usage.MeditationSession
import com.anchor.data.usage.MeditationSessionDao
import com.anchor.domain.AnchorDate
import com.anchor.domain.BreathingGuide
import com.anchor.domain.EveningSit
import com.anchor.domain.EveningSitGate
import com.anchor.domain.LockdownEnforcer
import com.anchor.ui.theme.AnchorTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The evening sit lockdown. Non-dismissable like the morning lock: back does
 * nothing, escaping it brings it straight back, and it is out of recents.
 * The only way out is enough recorded breathing for the day. Stopping early
 * still records what was sat, so several short sits add up.
 */
@AndroidEntryPoint
class EveningSitLockActivity : ComponentActivity() {

    @Inject lateinit var sessionDao: MeditationSessionDao
    @Inject lateinit var anchorDate: AnchorDate
    @Inject lateinit var enforcer: LockdownEnforcer
    @Inject lateinit var eveningSitGate: EveningSitGate
    @Inject lateinit var settingsProvider: SettingsProvider

    private enum class Stage { CHOOSE, BREATHING }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AnchorTheme {
                var stage by remember { mutableStateOf(Stage.CHOOSE) }
                var minutes by remember { mutableIntStateOf(BreathingGuide.DURATION_CHOICES_MINUTES.first()) }
                var requiredSeconds by remember { mutableIntStateOf(0) }
                var satSeconds by remember { mutableIntStateOf(0) }
                var loaded by remember { mutableStateOf(false) }

                // Re-read after every sit: enough breathing for the day ends the lock.
                suspend fun refresh() {
                    val settings = settingsProvider()
                    satSeconds = eveningSitGate.satSecondsToday(settings)
                    requiredSeconds = EveningSit.requiredSeconds(settings)
                    loaded = true
                    if (EveningSit.isSatisfied(settings, satSeconds)) {
                        enforcer.end()
                        finish()
                    }
                }

                LaunchedEffect(Unit) { refresh() }

                BackHandler(enabled = true) {
                    // Back never leaves. Mid-sit it only returns to the choice.
                    if (stage == Stage.BREATHING) stage = Stage.CHOOSE
                }

                Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(28.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (!loaded) return@Column
                        val leftMinutes = ((requiredSeconds - satSeconds + 59) / 60).coerceAtLeast(1)
                        when (stage) {
                            Stage.CHOOSE -> ChooseSit(
                                eyebrow = "Evening anchor",
                                title = if (satSeconds > 0) {
                                    "$leftMinutes more ${if (leftMinutes == 1) "minute" else "minutes"} of sitting, then the evening is yours."
                                } else {
                                    "Sit for $leftMinutes ${if (leftMinutes == 1) "minute" else "minutes"} before the evening opens up."
                                },
                                selected = minutes,
                                onSelect = { minutes = it },
                                onStart = { stage = Stage.BREATHING },
                                hint = "Breathe in for four, hold for two, out for six. Shorter sits add up; " +
                                    "the dialer and messages still work.",
                            )

                            Stage.BREATHING -> BreathingSit(
                                minutes = minutes,
                                onFinished = { sat ->
                                    record(sat) { lifecycleScope.launch { refresh(); stage = Stage.CHOOSE } }
                                },
                                onStopEarly = { sat ->
                                    if (sat >= MeditationActivity.MIN_RECORDED_SECONDS) {
                                        record(sat) { lifecycleScope.launch { refresh(); stage = Stage.CHOOSE } }
                                    } else {
                                        stage = Stage.CHOOSE
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun record(seconds: Int, then: () -> Unit) {
        val startedAt = anchorDate.nowMillis() - seconds * 1000L
        lifecycleScope.launch {
            sessionDao.insert(MeditationSession(insteadOfPackage = null, startedAtMillis = startedAt, seconds = seconds))
            then()
        }
    }

    /** Never let the system pause us into the background silently. */
    override fun onPause() {
        super.onPause()
        if (enforcer.isActive && !isFinishing) {
            enforcer.reassert()
        }
    }
}
