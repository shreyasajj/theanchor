package com.anchor.ui.lock

import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.anchor.data.settings.SettingsRepository
import com.anchor.data.usage.MeditationSession
import com.anchor.data.usage.MeditationSessionDao
import com.anchor.domain.AnchorDate
import com.anchor.domain.PauseLedger
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

    /** The ledger key for the pause this sit may serve. */
    private var subject: String? = null

    /** Set once the user has chosen what happens next, so onStop can tell
     *  a deliberate exit from simply walking away. */
    private var resolved = false

    private enum class Stage { CHOOSE, BREATHING, DONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        blockedPackage = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE)?.takeIf { it.isNotEmpty() }
        subject = intent.getStringExtra(EXTRA_SUBJECT)?.takeIf { it.isNotEmpty() } ?: blockedPackage
        val appLabel = blockedPackage?.let { AppLabels.app(this, it) }

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
                            Stage.CHOOSE -> ChooseSit(
                                eyebrow = "Instead of this",
                                title = appLabel?.let { "Sit for a minute rather than opening $it." }
                                    ?: "Sit for a minute.",
                                selected = minutes,
                                onSelect = { minutes = it },
                                onStart = { stage = Stage.BREATHING },
                                skipLabel = "Not now",
                                onSkip = { leave() },
                            )

                            Stage.BREATHING -> BreathingSit(
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

                            Stage.DONE -> SitDone(
                                satSeconds = satSeconds,
                                hint = appLabel?.let { "You can still open $it if you want to." }
                                    ?: "Back to your day.",
                                primaryLabel = "I'm good",
                                onPrimary = { leave() },
                                secondaryLabel = appLabel?.let { "Open $it anyway" },
                                onSecondary = { openAnyway() },
                            )
                        }
                    }
                }
            }
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
        subject?.let {
            PauseLedger.complete(it, System.currentTimeMillis())
            lifecycleScope.launch { settingsRepository.settlePause(it) }
        }
        finish()
    }

    /** Walking away mid-sit leaves the pause owed, like abandoning it. */
    override fun onStop() {
        if (!resolved && !isChangingConfigurations) subject?.let { PauseLedger.abandon(it) }
        super.onStop()
    }

    /** Leave without opening the app. */
    private fun leave() {
        resolved = true
        subject?.let { PauseLedger.abandon(it) }
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    companion object {
        const val EXTRA_BLOCKED_PACKAGE = "com.anchor.extra.BLOCKED_PACKAGE"
        const val EXTRA_SUBJECT = "com.anchor.extra.PAUSE_SUBJECT"

        /** Below this a sit is not worth recording. */
        const val MIN_RECORDED_SECONDS = 20

        fun intent(context: Context, blockedPackage: String? = null, subject: String? = blockedPackage): Intent =
            Intent(context, MeditationActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                if (blockedPackage != null) putExtra(EXTRA_BLOCKED_PACKAGE, blockedPackage)
                if (subject != null) putExtra(EXTRA_SUBJECT, subject)
            }
    }
}
