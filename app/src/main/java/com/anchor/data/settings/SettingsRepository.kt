package com.anchor.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.anchor.data.export.NoteFormat
import com.anchor.domain.Streak
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val MORNING_START = intPreferencesKey("morning_start")
        val MORNING_END = intPreferencesKey("morning_end")
        val EVENING_START = intPreferencesKey("evening_start")
        val EVENING_END = intPreferencesKey("evening_end")
        val DAY_RESET = intPreferencesKey("day_reset_minute")
        val BLOCKED = stringSetPreferencesKey("blocked_packages")
        val ALLOWLIST = stringSetPreferencesKey("allowlist_packages")
        val HA_URL = stringPreferencesKey("ha_url")
        val HA_TOKEN = stringPreferencesKey("ha_token")
        val HA_ENTITY = stringPreferencesKey("ha_entity")
        val MORNING_MODE = stringPreferencesKey("morning_mode")
        val MORNING_ROOMS = stringPreferencesKey("morning_rooms")
        val EVENING_MODE = stringPreferencesKey("evening_mode")
        val EVENING_ROOMS = stringPreferencesKey("evening_rooms")
        val ENFORCE_WITHOUT_HA = booleanPreferencesKey("enforce_without_ha")
        val RELOCK_BUBBLE = booleanPreferencesKey("relock_bubble")
        val PAUSES_OWED = stringSetPreferencesKey("pauses_owed")
        val ENFORCEMENT_MODE = stringPreferencesKey("enforcement_mode")
        val STREAK_START = stringPreferencesKey("streak_start_day")
        val LIMIT_BYPASSES = stringSetPreferencesKey("limit_bypasses")
        val EVENING_PROMPT = booleanPreferencesKey("evening_prompt_on_its_own")
        val EVENING_SIT_REQUIRED = booleanPreferencesKey("evening_sit_required")
        val EVENING_SIT_MINUTES = intPreferencesKey("evening_sit_minutes")
        val KILL_ENABLED = booleanPreferencesKey("kill_enabled")
        val KILL_ENTITY = stringPreferencesKey("kill_entity")
        val KILL_STATE = stringPreferencesKey("kill_state")
        val KILL_FAIL_OPEN = booleanPreferencesKey("kill_fail_open")
        val EXPORT_TREE = stringPreferencesKey("export_tree_uri")
        val NOTE_FORMAT = stringPreferencesKey("note_format")
        val JOPLIN_URL = stringPreferencesKey("joplin_url")
        val JOPLIN_TOKEN = stringPreferencesKey("joplin_token")
    }

    val settings: Flow<AnchorSettings> = dataStore.data.map { it.toSettings() }

    suspend fun current(): AnchorSettings = settings.first()

    /** Read-modify-write; the transform sees the current snapshot. */
    suspend fun update(transform: (AnchorSettings) -> AnchorSettings) {
        dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.MORNING_START] = next.morningStartMinute
            prefs[Keys.MORNING_END] = next.morningEndMinute
            prefs[Keys.EVENING_START] = next.eveningStartMinute
            prefs[Keys.EVENING_END] = next.eveningEndMinute
            prefs[Keys.DAY_RESET] = next.dayResetMinute
            prefs[Keys.BLOCKED] = next.blockedPackages
            prefs[Keys.ALLOWLIST] = next.allowlistPackages
            prefs[Keys.HA_URL] = next.haBaseUrl
            prefs[Keys.HA_TOKEN] = next.haToken
            prefs[Keys.HA_ENTITY] = next.haDeviceTrackerEntityId
            prefs[Keys.MORNING_MODE] = next.morningLocationMode.name
            prefs[Keys.MORNING_ROOMS] = formatRoomList(next.morningAllowedRooms)
            prefs[Keys.EVENING_MODE] = next.eveningLocationMode.name
            prefs[Keys.EVENING_ROOMS] = formatRoomList(next.eveningAllowedRooms)
            prefs[Keys.ENFORCE_WITHOUT_HA] = next.enforceWithoutHomeAssistant
            prefs[Keys.RELOCK_BUBBLE] = next.showRelockBubble
            prefs[Keys.PAUSES_OWED] = next.pausesOwed
            prefs[Keys.ENFORCEMENT_MODE] = next.enforcementMode.name
            prefs[Keys.LIMIT_BYPASSES] = next.limitBypasses
            prefs[Keys.EVENING_PROMPT] = next.eveningPromptOnItsOwn
            prefs[Keys.EVENING_SIT_REQUIRED] = next.eveningSitRequired
            prefs[Keys.EVENING_SIT_MINUTES] = next.eveningSitMinutes
            val streak = next.streakStartDay
            if (streak == null) prefs.remove(Keys.STREAK_START) else prefs[Keys.STREAK_START] = streak
            prefs[Keys.KILL_ENABLED] = next.killSwitchEnabled
            prefs[Keys.KILL_ENTITY] = next.killSwitchEntityId
            prefs[Keys.KILL_STATE] = next.killSwitchOverrideState
            prefs[Keys.KILL_FAIL_OPEN] = next.killSwitchFailOpenOnOutage
            prefs[Keys.NOTE_FORMAT] = next.noteFormat.name
            prefs[Keys.JOPLIN_URL] = next.joplinBaseUrl
            prefs[Keys.JOPLIN_TOKEN] = next.joplinToken
            val tree = next.exportTreeUri
            if (tree == null) prefs.remove(Keys.EXPORT_TREE) else prefs[Keys.EXPORT_TREE] = tree
        }
    }

    /** Record that [subject] was shown a pause it has not yet sat through. */
    suspend fun owePause(subject: String) {
        if (subject.isBlank()) return
        update { it.copy(pausesOwed = it.pausesOwed + subject) }
    }

    /** The pause was served, or no longer applies. */
    suspend fun settlePause(subject: String) {
        if (subject.isBlank()) return
        update { it.copy(pausesOwed = it.pausesOwed - subject) }
    }

    /** Starts the streak clock on [usageDay] if it has never been started. */
    suspend fun ensureStreakStarted(usageDay: String) {
        if (current().streakStartDay != null) return
        update { if (it.streakStartDay == null) it.copy(streakStartDay = usageDay) else it }
    }

    /**
     * The user walked through [subject]'s limit on [usageDay]. The streak
     * restarts tomorrow and the limit is waived until the next reset. Old
     * bypasses are dropped here so the set never grows.
     */
    suspend fun recordBreak(subject: String, usageDay: String) {
        update {
            it.copy(
                streakStartDay = Streak.startAfterBreak(usageDay),
                limitBypasses = it.limitBypasses.filter { key -> key.endsWith("|$usageDay") }.toSet() +
                    Streak.bypassKey(subject, usageDay),
            )
        }
    }

    private fun Preferences.toSettings(): AnchorSettings {
        val d = AnchorSettings()
        return AnchorSettings(
            morningStartMinute = this[Keys.MORNING_START] ?: d.morningStartMinute,
            morningEndMinute = this[Keys.MORNING_END] ?: d.morningEndMinute,
            eveningStartMinute = this[Keys.EVENING_START] ?: d.eveningStartMinute,
            eveningEndMinute = this[Keys.EVENING_END] ?: d.eveningEndMinute,
            dayResetMinute = this[Keys.DAY_RESET] ?: d.dayResetMinute,
            blockedPackages = this[Keys.BLOCKED] ?: d.blockedPackages,
            allowlistPackages = this[Keys.ALLOWLIST] ?: d.allowlistPackages,
            haBaseUrl = this[Keys.HA_URL] ?: d.haBaseUrl,
            haToken = this[Keys.HA_TOKEN] ?: d.haToken,
            haDeviceTrackerEntityId = this[Keys.HA_ENTITY] ?: d.haDeviceTrackerEntityId,
            morningLocationMode = this[Keys.MORNING_MODE]?.toLocationMode() ?: d.morningLocationMode,
            morningAllowedRooms = parseRoomList(this[Keys.MORNING_ROOMS] ?: ""),
            eveningLocationMode = this[Keys.EVENING_MODE]?.toLocationMode() ?: d.eveningLocationMode,
            eveningAllowedRooms = parseRoomList(this[Keys.EVENING_ROOMS] ?: ""),
            enforceWithoutHomeAssistant = this[Keys.ENFORCE_WITHOUT_HA] ?: d.enforceWithoutHomeAssistant,
            showRelockBubble = this[Keys.RELOCK_BUBBLE] ?: d.showRelockBubble,
            pausesOwed = this[Keys.PAUSES_OWED] ?: d.pausesOwed,
            enforcementMode = this[Keys.ENFORCEMENT_MODE]?.toEnforcementMode() ?: d.enforcementMode,
            streakStartDay = this[Keys.STREAK_START],
            limitBypasses = this[Keys.LIMIT_BYPASSES] ?: d.limitBypasses,
            eveningPromptOnItsOwn = this[Keys.EVENING_PROMPT] ?: d.eveningPromptOnItsOwn,
            eveningSitRequired = this[Keys.EVENING_SIT_REQUIRED] ?: d.eveningSitRequired,
            eveningSitMinutes = this[Keys.EVENING_SIT_MINUTES] ?: d.eveningSitMinutes,
            killSwitchEnabled = this[Keys.KILL_ENABLED] ?: d.killSwitchEnabled,
            killSwitchEntityId = this[Keys.KILL_ENTITY] ?: d.killSwitchEntityId,
            killSwitchOverrideState = this[Keys.KILL_STATE] ?: d.killSwitchOverrideState,
            killSwitchFailOpenOnOutage = this[Keys.KILL_FAIL_OPEN] ?: d.killSwitchFailOpenOnOutage,
            exportTreeUri = this[Keys.EXPORT_TREE],
            noteFormat = this[Keys.NOTE_FORMAT]?.toNoteFormat() ?: d.noteFormat,
            joplinBaseUrl = this[Keys.JOPLIN_URL] ?: d.joplinBaseUrl,
            joplinToken = this[Keys.JOPLIN_TOKEN] ?: d.joplinToken,
        )
    }

    private fun String.toLocationMode(): LocationMode =
        runCatching { LocationMode.valueOf(this) }.getOrDefault(LocationMode.AT_HOME)

    private fun String.toEnforcementMode(): EnforcementMode =
        runCatching { EnforcementMode.valueOf(this) }.getOrDefault(EnforcementMode.STRICT)

    private fun String.toNoteFormat(): NoteFormat =
        runCatching { NoteFormat.valueOf(this) }.getOrDefault(NoteFormat.PLAIN)
}
