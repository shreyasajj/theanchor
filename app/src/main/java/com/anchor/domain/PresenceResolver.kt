package com.anchor.domain

import com.anchor.data.db.Phase
import com.anchor.data.ha.HomeAssistantClient
import com.anchor.data.ha.LocationGate
import com.anchor.data.ha.Presence
import com.anchor.data.location.PhoneLocationSource
import com.anchor.data.location.PhonePresence
import com.anchor.data.location.SavedPlace
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.PresenceSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers "are you somewhere the protocol applies" for every gate. Only the
 * one source that decides (see [AnchorSettings.presenceSource]) is asked, so
 * no decision waits on both the network and the GPS. Never throws: both
 * sources already collapse every failure to [Presence.UNKNOWN].
 */
@Singleton
class PresenceResolver @Inject constructor(
    private val client: HomeAssistantClient,
    private val phone: PhoneLocationSource,
) {
    suspend fun resolve(settings: AnchorSettings, phase: Phase): Presence =
        when (settings.presenceSource) {
            PresenceSource.VACATION -> phoneAt(settings.vacationPlace)
            PresenceSource.PHONE_HOME -> phoneAt(settings.homePlace)
            PresenceSource.HOME_ASSISTANT -> {
                val result = client.fetchDeviceTracker(settings)
                when (phase) {
                    Phase.MORNING -> LocationGate.evaluate(
                        result,
                        settings.morningLocationMode,
                        settings.morningAllowedRooms,
                        settings.morningHomeStates,
                    )
                    Phase.EVENING -> LocationGate.evaluate(
                        result,
                        settings.eveningLocationMode,
                        settings.eveningAllowedRooms,
                        settings.eveningHomeStates,
                    )
                }
            }
        }

    /** No place saved means there is nothing to compare with; skip the GPS entirely. */
    private suspend fun phoneAt(place: SavedPlace?): Presence =
        if (place == null) Presence.UNKNOWN else PhonePresence.evaluate(phone.current(), place)
}
