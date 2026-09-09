package com.anchor.ui.lock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import com.anchor.data.db.CustomQuestionDao
import com.anchor.data.db.Phase
import com.anchor.domain.LockdownEnforcer
import com.anchor.domain.SubmitCheckIn
import com.anchor.ui.theme.AnchorTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The morning lockdown. Non-dismissable by design:
 *  - back is swallowed by [LockScreen]'s BackHandler
 *  - home / recents are countered by the accessibility service, which calls
 *    [LockdownEnforcer.reassert] whenever another app reaches the foreground
 *  - the task is excluded from recents (manifest) so it cannot be swiped away
 *
 * The accessibility service's allowlist means the dialer, messaging and
 * system surfaces still get through; see [com.anchor.domain.ForegroundAppDecider].
 */
@AndroidEntryPoint
class MorningLockActivity : ComponentActivity() {

    @Inject lateinit var questionDao: CustomQuestionDao
    @Inject lateinit var submitCheckIn: SubmitCheckIn
    @Inject lateinit var enforcer: LockdownEnforcer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val viewModel = ViewModelProvider(
            this,
            LockViewModelFactory(Phase.MORNING, questionDao, submitCheckIn),
        )[LockViewModel::class.java]

        setContent {
            AnchorTheme {
                val state by viewModel.state.collectAsState()

                LaunchedEffect(state.submitted) {
                    if (state.submitted) {
                        enforcer.end()
                        finish()
                    }
                }

                LockScreen(
                    eyebrow = "Morning anchor",
                    title = "Before the day begins.",
                    subtitle = "Two honest answers, then the phone is yours.",
                    state = state,
                    onAnswerChanged = viewModel::onAnswerChanged,
                    onSubmit = viewModel::submit,
                )
            }
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
