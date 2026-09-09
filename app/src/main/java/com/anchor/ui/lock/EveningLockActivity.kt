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
import com.anchor.domain.SubmitCheckIn
import com.anchor.ui.theme.AnchorTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The strict evening overlay. Unlike the morning lock this does NOT relaunch
 * itself: once the three questions are answered the user proceeds to the app
 * they opened, and the block stays lifted for the rest of the night.
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
                    if (state.submitted) finish()
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
}
