package com.anchor.data.ha

import com.anchor.data.settings.LocationMode

/**
 * Where the user is, relative to the places the protocol applies.
 *
 * IN_SCOPE     -> morning: lock down. evening: strict overlay.
 * OUT_OF_SCOPE -> morning: skip.      evening: simple 5s delay.
 * UNKNOWN      -> same as OUT_OF_SCOPE for behaviour; kept separate so the
 *                 dashboard can distinguish "you're out" from "HA is down".
 */
enum class Presence { IN_SCOPE, OUT_OF_SCOPE, UNKNOWN }

object LocationGate {

    /** States Home Assistant uses to say "I don't actually know". */
    private val UNKNOWN_STATES = setOf("unavailable", "unknown", "none", "")

    fun evaluate(
        result: HaResult,
        mode: LocationMode,
        allowedRooms: List<String>,
    ): Presence {
        val state = when (result) {
            is HaResult.Unavailable -> return Presence.UNKNOWN
            is HaResult.Ok -> result.state
        }
        if (state.state.lowercase() in UNKNOWN_STATES) return Presence.UNKNOWN

        return when (mode) {
            LocationMode.AT_HOME ->
                if (state.state.equals("home", ignoreCase = true)) Presence.IN_SCOPE
                else Presence.OUT_OF_SCOPE

            LocationMode.SPECIFIC_ROOMS -> {
                // The room usually appears in friendly_name (e.g. a per-room
                // BLE/mmWave tracker). Fall back to the raw state so a
                // zone-based tracker reporting "Bedroom" also works.
                val haystack = (state.friendlyName ?: state.state).lowercase()
                val matched = allowedRooms.any { room ->
                    room.isNotBlank() && haystack.contains(room.trim().lowercase())
                }
                if (matched) Presence.IN_SCOPE else Presence.OUT_OF_SCOPE
            }
        }
    }
}
