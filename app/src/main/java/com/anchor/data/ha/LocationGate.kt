package com.anchor.data.ha

import com.anchor.data.settings.DEFAULT_HOME_STATES
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
        homeStates: List<String> = DEFAULT_HOME_STATES,
    ): Presence {
        val state = when (result) {
            is HaResult.Unavailable -> return Presence.UNKNOWN
            is HaResult.Ok -> result.state
        }
        if (state.state.lowercase() in UNKNOWN_STATES) return Presence.UNKNOWN

        return when (mode) {
            LocationMode.AT_HOME -> {
                // An emptied list means "home", never "nothing matches":
                // clearing the field must not quietly switch the gate off.
                val wanted = homeStates.map { it.trim() }.filter { it.isNotEmpty() }
                    .ifEmpty { DEFAULT_HOME_STATES }
                val current = state.state.trim()
                if (wanted.any { it.equals(current, ignoreCase = true) }) Presence.IN_SCOPE
                else Presence.OUT_OF_SCOPE
            }

            LocationMode.SPECIFIC_ROOMS -> {
                // The room can be in friendly_name (a per-room BLE/mmWave
                // tracker) or in the state (room-presence integrations name
                // the device and report the room), so search both.
                val haystack = listOfNotNull(state.friendlyName, state.state).joinToString(" ").lowercase()
                val matched = allowedRooms.any { room ->
                    room.isNotBlank() && haystack.contains(room.trim().lowercase())
                }
                if (matched) Presence.IN_SCOPE else Presence.OUT_OF_SCOPE
            }
        }
    }
}
