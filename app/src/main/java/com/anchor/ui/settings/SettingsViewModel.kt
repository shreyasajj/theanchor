package com.anchor.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anchor.data.db.CustomQuestion
import com.anchor.data.db.CustomQuestionDao
import com.anchor.data.db.Phase
import com.anchor.data.db.SlotKey
import com.anchor.data.location.PhoneFix
import com.anchor.data.location.PhonePresence
import com.anchor.data.location.SavedPlace
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.LocationMode
import com.anchor.data.settings.SettingsRepository
import com.anchor.data.settings.parseRoomList
import com.anchor.data.usage.AppLimit
import com.anchor.data.usage.AppLimitDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which saved place a "Use current location" or radius edit is for. */
enum class PlaceTarget { HOME, VACATION }

/**
 * @param onScheduleChanged invoked with the new settings when a change
 *   requires re-arming the morning alarm. Passed as a lambda so the
 *   ViewModel stays Android-free and unit-testable.
 * @param currentFix reads the phone's location for "Use current location";
 *   a lambda for the same reason as [onScheduleChanged].
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val questionDao: CustomQuestionDao,
    private val appLimitDao: AppLimitDao,
    private val onScheduleChanged: (AnchorSettings) -> Unit,
    private val currentFix: suspend () -> PhoneFix? = { null },
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
            // Transform inside the repository's locked read-modify-write: two
            // quick edits to different fields must not overwrite each other.
            lateinit var before: AnchorSettings
            lateinit var after: AnchorSettings
            settingsRepository.update { current ->
                before = current
                transform(current).also { after = it }
            }
            if (after.morningStartMinute != before.morningStartMinute ||
                after.eveningStartMinute != before.eveningStartMinute ||
                after.eveningSitRequired != before.eveningSitRequired ||
                after.eveningPromptOnItsOwn != before.eveningPromptOnItsOwn
            ) {
                onScheduleChanged(after)
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

    fun setHomeStates(phase: Phase, raw: String) = updateSettings {
        val states = parseRoomList(raw)
        when (phase) {
            Phase.MORNING -> it.copy(morningHomeStates = states)
            Phase.EVENING -> it.copy(eveningHomeStates = states)
        }
    }

    // --- Places ---

    private val _placeMessage = MutableStateFlow<String?>(null)

    /** A one-line problem from the last "Use current location", or null. */
    val placeMessage: StateFlow<String?> = _placeMessage.asStateFlow()

    private val _capturing = MutableStateFlow<PlaceTarget?>(null)

    /**
     * The place "Use current location" is waiting on a fix for (up to 15 s),
     * or null. Its button shows "Locating…"; the other is disabled meanwhile.
     */
    val capturing: StateFlow<PlaceTarget?> = _capturing.asStateFlow()

    /**
     * Saves where the phone is now as [target], keeping any radius already
     * chosen. A missing or vague fix saves nothing: a place recorded from a
     * cell-tower guess would put "home" in the wrong street. [onSaved] runs
     * only after a place is stored, so the caller can then ask for background
     * location: the place is useless to the gates without it.
     */
    fun capturePlace(target: PlaceTarget, onSaved: () -> Unit = {}) {
        // One at a time: a second tap while locating would only race the first.
        if (_capturing.value != null) return
        _capturing.value = target
        _placeMessage.value = null
        viewModelScope.launch {
            try {
                val fix = currentFix()
                if (fix == null || !PhonePresence.isUsable(fix)) {
                    _placeMessage.value = CAPTURE_FAILED
                    return@launch
                }
                settingsRepository.update { s ->
                    s.withPlace(target) { old ->
                        SavedPlace(fix.latitude, fix.longitude, old?.radiusMeters ?: SavedPlace.DEFAULT_RADIUS_METERS)
                    }
                }
                onSaved()
            } finally {
                _capturing.value = null
            }
        }
    }

    /** Ignores anything but a whole number; clamps the rest. Needs a place to exist. */
    fun setPlaceRadius(target: PlaceTarget, raw: String) {
        val meters = raw.trim().toIntOrNull() ?: return
        updateSettings { s ->
            s.withPlace(target) { old -> old?.copy(radiusMeters = SavedPlace.clampRadius(meters)) }
        }
    }

    fun reportLocationDenied() {
        _placeMessage.value = PERMISSION_DENIED
    }

    fun reportPreciseLocationNeeded() {
        _placeMessage.value = PRECISE_NEEDED
    }

    private fun AnchorSettings.withPlace(
        target: PlaceTarget,
        change: (SavedPlace?) -> SavedPlace?,
    ): AnchorSettings = when (target) {
        PlaceTarget.HOME -> copy(homePlace = change(homePlace))
        PlaceTarget.VACATION -> copy(vacationPlace = change(vacationPlace))
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

    /**
     * Edits the limit covering [packageName], creating a single-app limit on
     * first use so the UI never has to.
     */
    fun setLimit(packageName: String, transform: (AppLimit) -> AppLimit) {
        viewModelScope.launch {
            val existing = appLimitDao.find(packageName) ?: AppLimit(packageName)
            appLimitDao.upsert(transform(existing))
        }
    }

    /** Edits an existing limit by id. */
    fun updateLimit(id: Long, transform: (AppLimit) -> AppLimit) {
        viewModelScope.launch {
            val existing = appLimitDao.findById(id) ?: return@launch
            appLimitDao.upsert(transform(existing))
        }
    }

    /**
     * A new limit for [packages]. Any of them already in another limit is
     * moved here, since an app can only be counted once; a limit left with
     * no apps is removed.
     */
    fun createLimit(packages: Set<String>, name: String = "") {
        if (packages.isEmpty()) return
        viewModelScope.launch {
            removeFromOtherLimits(packages, exceptId = null)
            appLimitDao.upsert(AppLimit(name = name, packages = packages))
        }
    }

    /** Replaces a limit's members, moving any that belonged elsewhere. */
    fun setLimitPackages(id: Long, packages: Set<String>) {
        viewModelScope.launch {
            val existing = appLimitDao.findById(id) ?: return@launch
            if (packages.isEmpty()) {
                appLimitDao.deleteById(id)
                return@launch
            }
            removeFromOtherLimits(packages, exceptId = id)
            appLimitDao.upsert(existing.copy(packages = packages))
        }
    }

    private suspend fun removeFromOtherLimits(packages: Set<String>, exceptId: Long?) {
        appLimitDao.all()
            .filter { it.id != exceptId && it.packages.any { p -> p in packages } }
            .forEach { other ->
                val remaining = other.packages - packages
                if (remaining.isEmpty()) appLimitDao.deleteById(other.id)
                else appLimitDao.upsert(other.copy(packages = remaining))
            }
    }

    fun clearLimit(packageName: String) {
        viewModelScope.launch { appLimitDao.delete(packageName) }
    }

    fun deleteLimit(id: Long) {
        viewModelScope.launch { appLimitDao.deleteById(id) }
    }

    companion object {
        const val CAPTURE_FAILED =
            "Couldn't get a precise enough location. Check that location is on, then try again."
        const val PERMISSION_DENIED = "Location permission is needed to save a place."
        const val PRECISE_NEEDED =
            "Turn on Precise location for The Anchor: approximate location can't tell home from the next street."
    }
}
