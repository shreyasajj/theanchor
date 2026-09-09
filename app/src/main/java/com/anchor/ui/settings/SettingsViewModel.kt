package com.anchor.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anchor.data.db.CustomQuestion
import com.anchor.data.db.CustomQuestionDao
import com.anchor.data.db.Phase
import com.anchor.data.db.SlotKey
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.LocationMode
import com.anchor.data.settings.SettingsRepository
import com.anchor.data.settings.parseRoomList
import com.anchor.data.usage.AppLimit
import com.anchor.data.usage.AppLimitDao
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * @param onScheduleChanged invoked when a change requires re-arming the
 *   morning alarm. Passed as a lambda so the ViewModel stays Android-free
 *   and unit-testable.
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val questionDao: CustomQuestionDao,
    private val appLimitDao: AppLimitDao,
    private val onScheduleChanged: () -> Unit,
) : ViewModel() {

    val settings: StateFlow<AnchorSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AnchorSettings())

    val morningQuestions: StateFlow<List<CustomQuestion>> =
        questionDao.observeIncludingDisabled(Phase.MORNING)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val eveningQuestions: StateFlow<List<CustomQuestion>> =
        questionDao.observeIncludingDisabled(Phase.EVENING)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val appLimits: StateFlow<List<AppLimit>> = appLimitDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun updateSettings(transform: (AnchorSettings) -> AnchorSettings) {
        viewModelScope.launch {
            val before = settingsRepository.current()
            val after = transform(before)
            settingsRepository.update { after }
            if (after.morningStartMinute != before.morningStartMinute) {
                onScheduleChanged()
            }
        }
    }

    fun setExportTree(uri: String) = updateSettings { it.copy(exportTreeUri = uri) }

    fun setRooms(phase: Phase, raw: String) = updateSettings {
        val rooms = parseRoomList(raw)
        when (phase) {
            Phase.MORNING -> it.copy(morningAllowedRooms = rooms)
            Phase.EVENING -> it.copy(eveningAllowedRooms = rooms)
        }
    }

    fun setLocationMode(phase: Phase, mode: LocationMode) = updateSettings {
        when (phase) {
            Phase.MORNING -> it.copy(morningLocationMode = mode)
            Phase.EVENING -> it.copy(eveningLocationMode = mode)
        }
    }

    fun toggleBlockedApp(packageName: String) = updateSettings {
        it.copy(blockedPackages = it.blockedPackages.toggle(packageName))
    }

    fun toggleAllowlistApp(packageName: String) = updateSettings {
        it.copy(allowlistPackages = it.allowlistPackages.toggle(packageName))
    }

    private fun Set<String>.toggle(value: String): Set<String> =
        if (value in this) this - value else this + value

    // --- Questions ---

    fun addQuestion(phase: Phase, prompt: String) {
        if (prompt.isBlank()) return
        viewModelScope.launch {
            val existing = questionDao.list(phase)
            questionDao.upsert(
                CustomQuestion(
                    phase = phase,
                    slotKey = SlotKey.custom(),
                    prompt = prompt.trim(),
                    sortOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                )
            )
        }
    }

    fun editQuestion(question: CustomQuestion, prompt: String) {
        if (prompt.isBlank()) return
        viewModelScope.launch { questionDao.upsert(question.copy(prompt = prompt.trim())) }
    }

    fun deleteQuestion(question: CustomQuestion) {
        viewModelScope.launch { questionDao.delete(question) }
    }

    /** @param delta -1 to move earlier, +1 to move later. */
    fun moveQuestion(question: CustomQuestion, delta: Int) {
        viewModelScope.launch {
            val ordered = questionDao.list(question.phase).sortedBy { it.sortOrder }
            val index = ordered.indexOfFirst { it.id == question.id }
            val target = index + delta
            if (index < 0 || target !in ordered.indices) return@launch

            val a = ordered[index]
            val b = ordered[target]
            questionDao.upsert(a.copy(sortOrder = b.sortOrder))
            questionDao.upsert(b.copy(sortOrder = a.sortOrder))
        }
    }

    // --- Usage limits ---

    /** Creates the row on first use, so the UI never has to. */
    fun setLimit(packageName: String, transform: (AppLimit) -> AppLimit) {
        viewModelScope.launch {
            val existing = appLimitDao.find(packageName) ?: AppLimit(packageName)
            appLimitDao.upsert(transform(existing))
        }
    }

    fun clearLimit(packageName: String) {
        viewModelScope.launch { appLimitDao.delete(packageName) }
    }
}
