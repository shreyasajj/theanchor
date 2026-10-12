package com.anchor.data.location

import com.anchor.data.ha.Presence

/** The phone's answer to "are you at [SavedPlace]", in the same terms as LocationGate. */
object PhonePresence {

    /** Worse than this and the reading cannot tell the house from the next street. */
    const val MAX_ACCURACY_METERS = 500f

    fun isUsable(fix: PhoneFix): Boolean = (fix.accuracyMeters ?: 0f) <= MAX_ACCURACY_METERS

    /** Fail-open: no fix, no place, or a vague fix is UNKNOWN, never IN_SCOPE. */
    fun evaluate(fix: PhoneFix?, place: SavedPlace?): Presence {
        if (fix == null || place == null || !isUsable(fix)) return Presence.UNKNOWN
        val distance = Geo.distanceMeters(fix.latitude, fix.longitude, place.latitude, place.longitude)
        return if (distance <= place.radiusMeters) Presence.IN_SCOPE else Presence.OUT_OF_SCOPE
    }
}
