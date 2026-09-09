package com.anchor.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.anchor.data.export.NoteFormat
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

    private fun String.toNoteFormat(): NoteFormat =
        runCatching { NoteFormat.valueOf(this) }.getOrDefault(NoteFormat.PLAIN)
}
