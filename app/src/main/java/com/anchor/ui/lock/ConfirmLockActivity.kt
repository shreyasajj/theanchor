package com.anchor.ui.lock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.anchor.domain.LimitGate
import com.anchor.ui.components.AnchorCard
import com.anchor.ui.components.Eyebrow
import com.anchor.ui.theme.AnchorTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A small sheet over the current app, opened by the floating button:
 * "Lock this app now?" Confirming records an early lock, which ends the
 * session, and then shows exactly what the session running out shows: the
 * pause, from which Continue is a new open, or the blocked screen.
 */
@AndroidEntryPoint
class ConfirmLockActivity : ComponentActivity() {

    @Inject lateinit var limitGate: LimitGate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: run { finish(); return }
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)

        setContent {
            AnchorTheme {
                var busy by remember { mutableStateOf(false) }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f))
                        // Tapping the scrim cancels.
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (!busy) finish()
                        }
                        .navigationBarsPadding()
                        .padding(20.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    AnchorCard(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                        ) { },
                    ) {
                        Eyebrow("Lock early")
                        Spacer(Modifier.height(10.dp))
                        Text("Lock $label now?", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "This ends the session now. You will be asked before going back in, " +
                                "and going back in is a new open. Any cooldown starts now.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(20.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { finish() }, enabled = !busy) { Text("Not yet") }
                            Spacer(Modifier.padding(horizontal = 6.dp))
                            Button(
                                onClick = {
                                    busy = true
                                    lifecycleScope.launch {
                                        val locked = limitGate.lockEarly(packageName)
                                        val next = if (locked) {
                                            SessionEndScreens.intentFor(this@ConfirmLockActivity, limitGate, packageName)
                                        } else null
                                        startActivity(
                                            next ?: Intent(Intent.ACTION_MAIN)
                                                .addCategory(Intent.CATEGORY_HOME)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                        finish()
                                    }
                                },
                                enabled = !busy,
                                shape = MaterialTheme.shapes.small,
                            ) { Text(if (busy) "Locking…" else "Lock it") }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "com.anchor.extra.LOCK_PACKAGE"

        fun intent(context: Context, packageName: String): Intent =
            Intent(context, ConfirmLockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(EXTRA_PACKAGE, packageName)
            }
    }
}
