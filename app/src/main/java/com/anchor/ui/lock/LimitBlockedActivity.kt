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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.anchor.domain.LimitReason
import com.anchor.ui.components.Eyebrow
import com.anchor.ui.theme.AnchorTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
            LimitReason.COOLDOWN, LimitReason.SESSION_CAP -> {
                val minutes = minutesUntil(resetsAtMillis, nowMillis)
                "Available again in $minutes ${if (minutes == 1L) "minute" else "minutes"}."
            }
            LimitReason.DAILY_TIME, LimitReason.DAILY_OPENS ->
                "This resets at ${formatTime(resetsAtMillis)}."
        }

    /** Rounded up, floored at 1: "0 minutes" would read as a bug. */
    private fun minutesUntil(targetMillis: Long, nowMillis: Long): Long =
        maxOf(1L, (targetMillis - nowMillis + 59_999) / 60_000)

    private fun formatTime(millis: Long): String =
        DateTimeFormatter.ofPattern("HH:mm")
            .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
}

/**
 * A wall, not a toll. There is no way through; the remote kill switch is the
 * deliberate escape hatch, and it requires opening Home Assistant.
 */
class LimitBlockedActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val reason = LimitReason.valueOf(
            intent.getStringExtra(EXTRA_REASON) ?: LimitReason.DAILY_TIME.name
        )
        val resetsAt = intent.getLongExtra(EXTRA_RESETS_AT, System.currentTimeMillis())

        setContent {
            AnchorTheme {
                BackHandler(enabled = true) { }
                Blocked(
                    title = LimitCopy.title(reason),
                    body = LimitCopy.body(reason, resetsAt, System.currentTimeMillis()),
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

    @Composable
    private fun Blocked(title: String, body: String, onDismiss: () -> Unit) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Eyebrow("Limit reached", color = MaterialTheme.colorScheme.error)
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
                Spacer(Modifier.height(48.dp))
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = MaterialTheme.shapes.small,
                ) { Text("Back to home", style = MaterialTheme.typography.titleMedium) }
            }
        }
    }

    companion object {
        const val EXTRA_REASON = "com.anchor.extra.LIMIT_REASON"
        const val EXTRA_RESETS_AT = "com.anchor.extra.RESETS_AT"

        fun intent(context: Context, reason: LimitReason, resetsAtMillis: Long): Intent =
            Intent(context, LimitBlockedActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(EXTRA_REASON, reason.name)
                putExtra(EXTRA_RESETS_AT, resetsAtMillis)
            }
    }
}
