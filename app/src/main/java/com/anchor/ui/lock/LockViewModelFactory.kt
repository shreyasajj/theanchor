package com.anchor.ui.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.anchor.data.db.CustomQuestionDao
import com.anchor.data.db.Phase
import com.anchor.domain.SubmitCheckIn

/**
 * The phase is an Activity-level constant rather than a saved-state argument,
 * so a plain factory is simpler here than @HiltViewModel + assisted injection.
 */
class LockViewModelFactory(
    private val phase: Phase,
    private val questionDao: CustomQuestionDao,
    private val submitCheckIn: SubmitCheckIn,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        LockViewModel(
            phase = phase,
            questionDao = questionDao,
            submitCheckIn = { p, answers -> submitCheckIn(p, answers) },
        ) as T
}
