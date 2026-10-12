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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.anchor.ui.components.Eyebrow
import com.anchor.ui.settings.sections.formatMinute
import com.anchor.ui.theme.AnchorTheme

/** All bedroom-screen copy in one testable place. */
object BedroomCopy {
    const val EYEBROW = "Bedroom"
    const val TITLE = "Not in the bedroom"

    fun body(appName: String?, endMinute: Int): String =
        "${appName ?: "This app"} stays closed in the bedroom until ${formatMinute(endMinute)}."
}

/**
 * The bedroom's wall. There is no way through: answering the questions does
 * not open it, and the remote kill switch is the deliberate escape hatch.
 * Back and the one button both go to the home screen rather than back to the
 * app, which would only bring this screen up again.
 */
class BedroomBlockedActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val blockedPackage = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE)
        val endMinute = intent.getIntExtra(EXTRA_END_MINUTE, 7 * 60)
        val appName = blockedPackage?.let { AppLabels.app(this, it) }

        setContent {
            AnchorTheme {
                BackHandler(enabled = true) { goHome() }
                Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(28.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Eyebrow(BedroomCopy.EYEBROW, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(20.dp))
                        Text(BedroomCopy.TITLE, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            BedroomCopy.body(appName, endMinute),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(44.dp))
                        Button(
                            onClick = ::goHome,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = MaterialTheme.shapes.small,
                        ) { Text("Go to home screen", style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
        }
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    companion object {
        const val EXTRA_BLOCKED_PACKAGE = "com.anchor.extra.BLOCKED_PACKAGE"
        const val EXTRA_END_MINUTE = "com.anchor.extra.END_MINUTE"

        fun intent(context: Context, blockedPackage: String, endMinute: Int): Intent =
            Intent(context, BedroomBlockedActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(EXTRA_BLOCKED_PACKAGE, blockedPackage)
                putExtra(EXTRA_END_MINUTE, endMinute)
            }
    }
}
