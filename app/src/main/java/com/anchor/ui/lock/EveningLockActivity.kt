package com.anchor.ui.lock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import com.anchor.data.db.CustomQuestionDao
import com.anchor.data.db.Phase
import com.anchor.domain.SubmitCheckIn
import com.anchor.ui.theme.AnchorTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The strict evening overlay. Unlike the morning lock this does NOT relaunch
 * itself: once the three questions are answered the user proceeds to the app
 * they opened, and the block stays lifted for the rest of the night.
 *
 * With "ask the evening questions on their own" it is also shown with no app
 * behind it, and re-shown once a minute while the phone is in use until it
 * is answered.
 */
@AndroidEntryPoint
class EveningLockActivity : ComponentActivity() {

    @Inject lateinit var questionDao: CustomQuestionDao
    @Inject lateinit var submitCheckIn: SubmitCheckIn

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val viewModel = ViewModelProvider(
            this,
            LockViewModelFactory(Phase.EVENING, questionDao, submitCheckIn),
        )[LockViewModel::class.java]

        setContent {
            AnchorTheme {
                val state by viewModel.state.collectAsState()

                LaunchedEffect(state.submitted) {
                    if (state.submitted) {
                        // With nothing behind us, finishing would reveal
                        // whatever was open before; go home instead.
                        if (intent.getBooleanExtra(EXTRA_UNPROMPTED, false)) {
                            startActivity(
                                Intent(Intent.ACTION_MAIN)
                                    .addCategory(Intent.CATEGORY_HOME)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                        finish()
                    }
                }

                LockScreen(
                    eyebrow = "Evening anchor",
                    title = "Three moments from today.",
                    subtitle = "Answer once and the evening is open until morning.",
                    state = state,
                    onAnswerChanged = viewModel::onAnswerChanged,
                    onSubmit = viewModel::submit,
                )
            }
        }
    }

    companion object {
        const val EXTRA_UNPROMPTED = "com.anchor.extra.EVENING_UNPROMPTED"

        /** The questions on their own, not in front of an app. */
        fun unpromptedIntent(context: Context): Intent =
            Intent(context, EveningLockActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(EXTRA_UNPROMPTED, true)
            }
    }
}
