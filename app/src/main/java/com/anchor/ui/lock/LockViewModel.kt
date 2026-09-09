package com.anchor.ui.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anchor.data.db.CustomQuestion
import com.anchor.data.db.CustomQuestionDao
import com.anchor.data.db.Phase
import com.anchor.data.export.ExportResult
import com.anchor.domain.SubmitResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LockUiState(
    val questions: List<CustomQuestion> = emptyList(),
    val answers: Map<String, String> = emptyMap(),
    val isSubmitting: Boolean = false,
    val submitted: Boolean = false,
    val exportWarning: String? = null,
) {
    /** Every rendered question needs a non-blank answer. */
    val canSubmit: Boolean
        get() = questions.isNotEmpty() &&
            !isSubmitting &&
            questions.all { !answers[it.slotKey].isNullOrBlank() }

    /** How many of the questions are answered; drives the progress hint. */
    val answeredCount: Int
        get() = questions.count { !answers[it.slotKey].isNullOrBlank() }
}

/**
 * Backs both lock screens. Takes [submitCheckIn] as a function rather than
 * the use-case class so it can be faked in a plain JVM test.
 */
class LockViewModel(
    private val phase: Phase,
    private val questionDao: CustomQuestionDao,
    private val submitCheckIn: suspend (Phase, Map<String, String>) -> SubmitResult,
) : ViewModel() {

    private val _state = MutableStateFlow(LockUiState())
    val state: StateFlow<LockUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(questions = questionDao.list(phase))
        }
    }

    fun onAnswerChanged(slotKey: String, value: String) {
        _state.value = _state.value.copy(answers = _state.value.answers + (slotKey to value))
    }

    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return

        _state.value = current.copy(isSubmitting = true)
        viewModelScope.launch {
            val answers = current.questions.associate { q ->
                q.slotKey to (current.answers[q.slotKey]?.trim() ?: "")
            }
            val result = submitCheckIn(phase, answers)
            _state.value = _state.value.copy(
                isSubmitting = false,
                submitted = true,
                exportWarning = warningFor(result),
            )
        }
    }

    /**
     * Export problems never block the user: the answers are already saved.
     * They surface as a one-line warning.
     */
    private fun warningFor(result: SubmitResult): String? = when (result.export) {
        is ExportResult.Written -> null
        is ExportResult.NoDirectoryConfigured ->
            "Saved locally. Pick an export folder in Settings to write Markdown files."
        is ExportResult.Failed ->
            "Saved to the database, but the Markdown file could not be written."
    }
}
