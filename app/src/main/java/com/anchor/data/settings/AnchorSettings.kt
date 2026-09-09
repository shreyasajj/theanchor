package com.anchor.data.settings

import com.anchor.data.export.NoteFormat

enum class LocationMode { AT_HOME, SPECIFIC_ROOMS }

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
}

/** Splits a comma-separated room list, trimming and dropping blanks. */
fun parseRoomList(raw: String): List<String> =
    raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

/** Renders a room list back into the comma-separated form shown in Settings. */
fun formatRoomList(rooms: List<String>): String = rooms.joinToString(", ")
