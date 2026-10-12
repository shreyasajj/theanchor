package com.anchor.domain

import com.anchor.data.ha.HomeAssistantClient
import com.anchor.data.ha.KillSwitch
import com.anchor.data.ha.LocationGate
import com.anchor.data.ha.Presence
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.LocationMode
import com.anchor.data.settings.PresenceSource
import com.anchor.data.settings.SettingsProvider
import javax.inject.Inject
import javax.inject.Singleton

enum class BedroomSkip {
    DISABLED,
    NOT_BLOCKED_APP,
    OUTSIDE_HOURS,
    VACATION,
    OVERRIDE_ACTIVE,
    LOCATION_UNKNOWN,
    NOT_IN_BEDROOM,
}

sealed interface BedroomDecision {
    /** No way in; the app is available again at [endMinute]. */
    data class Block(val endMinute: Int) : BedroomDecision
    data class Allow(val reason: BedroomSkip) : BedroomDecision
}

/**
 * Decides whether a blocked app is walled off because you are in the
 * bedroom. Cheapest checks first; the network last, kill switch before the
 * tracker. Only a confirmed room reading blocks: unlike the evening gate,
 * "ask anyway" never turns an unknown location into a wall.
 */
@Singleton
class BedroomGate @Inject constructor(
    private val settingsProvider: SettingsProvider,
    private val client: HomeAssistantClient,
    private val killSwitch: KillSwitch,
    private val anchorDate: AnchorDate,
) {
    suspend fun decide(packageName: String): BedroomDecision {
        val settings = settingsProvider()
        if (!covers(settings, packageName)) {
            return BedroomDecision.Allow(
                if (!settings.bedroomBlockEnabled) BedroomSkip.DISABLED else BedroomSkip.NOT_BLOCKED_APP
            )
        }
        if (!TimeWindow.contains(anchorDate.minuteOfDay(), settings.bedroomStartMinute, settings.bedroomEndMinute)) {
            return BedroomDecision.Allow(BedroomSkip.OUTSIDE_HOURS)
        }
        // On vacation you are not in this house's bedroom.
        if (settings.presenceSource == PresenceSource.VACATION) return BedroomDecision.Allow(BedroomSkip.VACATION)
        if (killSwitch.blockingDisabled(settings)) return BedroomDecision.Allow(BedroomSkip.OVERRIDE_ACTIVE)

        val presence = LocationGate.evaluate(
            client.fetchDeviceTracker(settings),
            LocationMode.SPECIFIC_ROOMS,
            settings.bedroomRooms,
        )
        return when (presence) {
            Presence.IN_SCOPE -> BedroomDecision.Block(settings.bedroomEndMinute)
            Presence.OUT_OF_SCOPE -> BedroomDecision.Allow(BedroomSkip.NOT_IN_BEDROOM)
            Presence.UNKNOWN -> BedroomDecision.Allow(BedroomSkip.LOCATION_UNKNOWN)
        }
    }

    /**
     * Whether the watcher should keep an eye on [packageName]. No hours and
     * no network: an app opened just before the hours is caught once they
     * start.
     */
    suspend fun watches(packageName: String): Boolean = covers(settingsProvider(), packageName)

    private fun covers(settings: AnchorSettings, packageName: String): Boolean =
        settings.bedroomBlockEnabled &&
            packageName != EveningGate.OWN_PACKAGE &&
            packageName in settings.blockedPackages
}
