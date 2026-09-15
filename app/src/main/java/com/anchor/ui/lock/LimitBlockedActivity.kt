package com.anchor.ui.lock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.anchor.data.settings.EnforcementMode
import com.anchor.data.settings.SettingsRepository
import com.anchor.domain.AnchorDate
import com.anchor.domain.LimitGate
import com.anchor.domain.LimitReason
import com.anchor.domain.PauseLedger
import com.anchor.domain.Streak
import com.anchor.ui.components.Eyebrow
import com.anchor.ui.components.Hint
import com.anchor.ui.theme.AnchorTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** All user-facing limit copy in one testable place. */
object LimitCopy {

    fun title(reason: LimitReason): String = when (reason) {
        LimitReason.DAILY_TIME -> "Time's up for today"
        LimitReason.DAILY_OPENS -> "That's the last open for today"
        LimitReason.COOLDOWN -> "Not just yet"
        LimitReason.SESSION_CAP -> "That's the session"
    }

    fun body(reason: LimitReason, resetsAtMillis: Long, nowMillis: Long): String =
        when (reason) {
            LimitReason.SESSION_CAP -> if (resetsAtMillis <= nowMillis) {
                "That was the whole session. Opening it again starts a new one."
            } else {
                "That was the session. Available again in ${minutesPhrase(resetsAtMillis, nowMillis)}."
            }
            LimitReason.COOLDOWN -> "Available again in ${minutesPhrase(resetsAtMillis, nowMillis)}."
            LimitReason.DAILY_TIME, LimitReason.DAILY_OPENS ->
                "This resets at ${formatTime(resetsAtMillis)}."
        }

    /** The streak-mode escape, worded by what it costs. */
    fun breakLabel(streakDays: Int): String = when (streakDays) {
        0 -> "Open anyway"
        1 -> "Open anyway and end a 1-day streak"
        else -> "Open anyway and end a $streakDays-day streak"
    }

    private fun minutesPhrase(targetMillis: Long, nowMillis: Long): String {
        val minutes = minutesUntil(targetMillis, nowMillis)
        return "$minutes ${if (minutes == 1L) "minute" else "minutes"}"
    }

    /** Rounded up, floored at 1: "0 minutes" would read as a bug. */
    private fun minutesUntil(targetMillis: Long, nowMillis: Long): Long =
        maxOf(1L, (targetMillis - nowMillis + 59_999) / 60_000)

    private fun formatTime(millis: Long): String =
        DateTimeFormatter.ofPattern("HH:mm")
            .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
}

/**
 * A wall, not a toll, in strict mode: there is no way through, and the
 * remote kill switch is the deliberate escape hatch. In streak mode there is
 * a door, and it costs the streak.
 */
@AndroidEntryPoint
class LimitBlockedActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var limitGate: LimitGate
    @Inject lateinit var anchorDate: AnchorDate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val reason = LimitReason.valueOf(
            intent.getStringExtra(EXTRA_REASON) ?: LimitReason.DAILY_TIME.name
        )
        val resetsAt = intent.getLongExtra(EXTRA_RESETS_AT, System.currentTimeMillis())
        val blockedPackage = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE)

        setContent {
            AnchorTheme {
                var streakDays by remember { mutableStateOf<Int?>(null) }
                var groupLabel by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
                    val settings = settingsRepository.current()
                    val limit = blockedPackage?.let { limitGate.limitFor(it) }
                    groupLabel = AppLabels.limit(this@LimitBlockedActivity, limit, blockedPackage)
                    if (settings.enforcementMode == EnforcementMode.STREAK) {
                        streakDays = Streak.count(settings.streakStartDay, anchorDate.usageDay(settings.dayResetMinute))
                    }
                }

                BackHandler(enabled = true) { }
                Blocked(
                    title = LimitCopy.title(reason),
                    body = LimitCopy.body(reason, resetsAt, System.currentTimeMillis()),
                    groupLabel = groupLabel,
                    streakDays = streakDays,
                    onMeditate = {
                        startActivity(MeditationActivity.intent(this, blockedPackage))
                        finish()
                    },
                    onBreak = { blockedPackage?.let { walkThrough(it) } },
                    onDismiss = {
                        // Send the user home rather than back to the app they
                        // were blocked from, which would just re-trigger us.
                        startActivity(
                            Intent(Intent.ACTION_MAIN)
                                .addCategory(Intent.CATEGORY_HOME)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        finish()
                    },
                )
            }
        }
    }

    /**
     * Streak mode's door: the streak restarts tomorrow, this limit is waived
     * until the next reset, and the app underneath is let through.
     */
    private fun walkThrough(packageName: String) {
        lifecycleScope.launch {
            val settings = settingsRepository.current()
            val subject = limitGate.limitFor(packageName)?.subject ?: packageName
            settingsRepository.recordBreak(subject, anchorDate.usageDay(settings.dayResetMinute))
            PauseLedger.complete(subject, System.currentTimeMillis())
            finish()
        }
    }

    @Composable
    private fun Blocked(
        title: String,
        body: String,
        groupLabel: String?,
        streakDays: Int?,
        onMeditate: () -> Unit,
        onBreak: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Eyebrow(groupLabel?.let { "Limit reached: $it" } ?: "Limit reached", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(20.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(44.dp))
                Button(
                    onClick = onMeditate,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = MaterialTheme.shapes.small,
                ) { Text("Breathe for a minute", style = MaterialTheme.typography.titleMedium) }
                if (streakDays != null) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onBreak,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = MaterialTheme.shapes.small,
                    ) { Text(LimitCopy.breakLabel(streakDays), style = MaterialTheme.typography.titleMedium) }
                    Hint(
                        "The limit is waived until the next reset. The streak starts again tomorrow.",
                        Modifier.padding(top = 8.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss) { Text("Back to home") }
            }
        }
    }

    companion object {
        const val EXTRA_REASON = "com.anchor.extra.LIMIT_REASON"
        const val EXTRA_RESETS_AT = "com.anchor.extra.RESETS_AT"
        const val EXTRA_BLOCKED_PACKAGE = "com.anchor.extra.BLOCKED_PACKAGE"

        fun intent(
            context: Context,
            reason: LimitReason,
            resetsAtMillis: Long,
            blockedPackage: String? = null,
        ): Intent =
            Intent(context, LimitBlockedActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(EXTRA_REASON, reason.name)
                putExtra(EXTRA_RESETS_AT, resetsAtMillis)
                if (blockedPackage != null) putExtra(EXTRA_BLOCKED_PACKAGE, blockedPackage)
            }
    }
}
