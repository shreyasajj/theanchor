package com.anchor.data.settings

import com.anchor.data.export.NoteFormat
import com.anchor.data.location.SavedPlace

enum class LocationMode { AT_HOME, SPECIFIC_ROOMS }

/** Where presence comes from when vacation mode is not deciding it. */
enum class LocationSource { HOME_ASSISTANT, PHONE }

/** Which reading actually decides presence right now (spec §1.1). */
enum class PresenceSource { HOME_ASSISTANT, PHONE_HOME, VACATION }

/** What "At Home" mode has always meant; also what an emptied list falls back to. */
val DEFAULT_HOME_STATES: List<String> = listOf("home")

/**
 * What a spent limit does. STRICT blocks the app until it resets. STREAK
 * lets the user through on request, at the cost of the streak.
 */
enum class EnforcementMode { STRICT, STREAK }

/**
 * An immutable snapshot of every user-configurable value. Read it once at
 * the moment of a blocking decision and never cache it across decisions,
 * since the kill switch and the schedule can change between them.
 */
data class AnchorSettings(
    // --- Schedule (minutes since midnight, local time) ---
    val morningStartMinute: Int = 5 * 60,
    val morningEndMinute: Int = 12 * 60,
    val eveningStartMinute: Int = 20 * 60,
    val eveningEndMinute: Int = 5 * 60,

    /**
     * When usage limits reset. Default 04:00 rather than midnight so a late
     * night does not silently consume the next day's budget.
     */
    val dayResetMinute: Int = 4 * 60,

    // --- App blocking ---
    val blockedPackages: Set<String> = emptySet(),
    val allowlistPackages: Set<String> = emptySet(),

    // --- Home Assistant ---
    val haBaseUrl: String = "",
    val haToken: String = "",
    val haDeviceTrackerEntityId: String = "",

    val morningLocationMode: LocationMode = LocationMode.AT_HOME,
    val morningAllowedRooms: List<String> = emptyList(),
    val eveningLocationMode: LocationMode = LocationMode.AT_HOME,
    val eveningAllowedRooms: List<String> = emptyList(),

    /** Entity states that count as in scope in AT_HOME mode, matched case-insensitively. */
    val morningHomeStates: List<String> = DEFAULT_HOME_STATES,
    val eveningHomeStates: List<String> = DEFAULT_HOME_STATES,

    // --- Phone location ---
    val locationSource: LocationSource = LocationSource.HOME_ASSISTANT,
    /** The geofence used when [locationSource] is PHONE. */
    val homePlace: SavedPlace? = null,
    /**
     * While on with a [vacationPlace], the phone's location against that
     * place decides presence and Home Assistant is not asked. On without a
     * place changes nothing.
     */
    val vacationMode: Boolean = false,
    val vacationPlace: SavedPlace? = null,

    // --- Bedroom block ---
    /**
     * During [bedroomStartMinute]–[bedroomEndMinute], blocked apps do not
     * open at all while Home Assistant places you in one of [bedroomRooms],
     * even after the evening questions. Only a confirmed room reading blocks.
     */
    val bedroomBlockEnabled: Boolean = false,
    val bedroomRooms: List<String> = listOf("Bedroom"),
    val bedroomStartMinute: Int = 22 * 60,
    val bedroomEndMinute: Int = 7 * 60,

    /**
     * When the location cannot be read (Home Assistant not configured or
     * unreachable, or no usable phone fix), still ask the questions. Off by
     * default, which is the fail-open behaviour: no reading means no lock and
     * a 5s pause. A confirmed "away" reading always skips, whatever this says.
     * The name predates the phone source and is kept for the stored key.
     */
    val enforceWithoutHomeAssistant: Boolean = false,

    /**
     * Show the floating lock button while an app with a limit is open, for
     * ending its session early.
     */
    val showRelockBubble: Boolean = true,

    /**
     * Limits (by [com.anchor.data.usage.AppLimit.subject]) that have been
     * shown a pause never sat through. Persisted, not held in memory: the
     * app's process restarts routinely, and an in-memory debt is forgotten,
     * which let a walked-away-from pause be treated as a session the user
     * could simply rejoin.
     */
    val pausesOwed: Set<String> = emptySet(),

    // --- Streaks ---
    val enforcementMode: EnforcementMode = EnforcementMode.STRICT,

    /**
     * The usage day the current streak began, ISO date. Null until the first
     * dashboard visit. After a break it is set to the following day, so the
     * day of the break counts for nothing.
     */
    val streakStartDay: String? = null,

    /**
     * Limits the user chose to walk through today, as "subject|usageDay".
     * A bypass lasts until the next daily reset.
     */
    val limitBypasses: Set<String> = emptySet(),

    /**
     * Ask the evening questions when the window opens and you are in scope,
     * not only when a blocked app is opened. Otherwise a spent limit can keep
     * the questions from ever being asked.
     */
    val eveningPromptOnItsOwn: Boolean = false,

    // --- Evening sit ---
    /**
     * Lock the phone during the evening window, when in scope, until the
     * day's guided breathing reaches [eveningSitMinutes].
     */
    val eveningSitRequired: Boolean = false,
    val eveningSitMinutes: Int = 5,

    // --- Remote kill switch ---
    val killSwitchEnabled: Boolean = false,
    val killSwitchEntityId: String = "",
    /** The state value that DISABLES blocking. */
    val killSwitchOverrideState: String = "on",
    /**
     * When true, an unreachable Home Assistant counts as an active override
     * and all blocking is skipped. Off by default: turning it on makes losing
     * network access a one-gesture bypass.
     */
    val killSwitchFailOpenOnOutage: Boolean = false,

    // --- Export ---
    /** Persisted SAF tree URI, or null until the user picks a folder. */
    val exportTreeUri: String? = null,
    val noteFormat: NoteFormat = NoteFormat.PLAIN,
    val joplinBaseUrl: String = "",
    val joplinToken: String = "",
) {
    /** True when there is enough configuration to attempt an HA call at all. */
    val homeAssistantConfigured: Boolean
        get() = haBaseUrl.isNotBlank() && haToken.isNotBlank() &&
            haDeviceTrackerEntityId.isNotBlank()

    val presenceSource: PresenceSource
        get() = when {
            vacationMode && vacationPlace != null -> PresenceSource.VACATION
            locationSource == LocationSource.PHONE -> PresenceSource.PHONE_HOME
            else -> PresenceSource.HOME_ASSISTANT
        }

    /** The current setup reads the phone's location, so it needs the permission. */
    val usesPhoneLocation: Boolean
        get() = locationSource == LocationSource.PHONE || vacationMode
}

/** Splits a comma-separated room list, trimming and dropping blanks. */
fun parseRoomList(raw: String): List<String> =
    raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

/** Renders a room list back into the comma-separated form shown in Settings. */
fun formatRoomList(rooms: List<String>): String = rooms.joinToString(", ")
