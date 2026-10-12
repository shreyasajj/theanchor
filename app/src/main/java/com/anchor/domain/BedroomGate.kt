package com.anchor.domain

import com.anchor.data.ha.HomeAssistantClient
import com.anchor.data.ha.KillSwitch
import com.anchor.data.ha.LocationGate
import com.anchor.data.ha.Presence
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.LocationMode
import com.anchor.data.settings.PresenceSource
import com.anchor.data.settings.SettingsProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 *
 * It is asked on every window event of an app, ahead of the debounce, and
 * an app raises several per launch and more for each screen inside it. So
 * events for one app wait for one lookup, its answer is reused for
 * [MEMORY_MILLIS], and the kill switch and tracker are fetched together.
 */
@Singleton
class BedroomGate @Inject constructor(
    private val settingsProvider: SettingsProvider,
    private val client: HomeAssistantClient,
    private val killSwitch: KillSwitch,
    private val anchorDate: AnchorDate,
) {
    private class Remembered(val atMillis: Long, val decision: BedroomDecision)

    private val guard = Mutex()
    private val perPackage = HashMap<String, Mutex>()
    private val recent = HashMap<String, Remembered>()

    suspend fun decide(packageName: String): BedroomDecision {
        val packageLock = guard.withLock { perPackage.getOrPut(packageName) { Mutex() } }
        return packageLock.withLock {
            val now = anchorDate.nowMillis()
            val remembered = guard.withLock {
                recent.values.removeAll { now - it.atMillis !in 0 until MEMORY_MILLIS }
                recent[packageName]
            }
            remembered?.decision ?: decideNow(packageName).also { decision ->
                guard.withLock { recent[packageName] = Remembered(now, decision) }
            }
        }
    }

    private suspend fun decideNow(packageName: String): BedroomDecision {
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

        // Both at once: one round trip of waiting, not two.
        val (overridden, reading) = coroutineScope {
            val override = async { killSwitch.blockingDisabled(settings) }
            val tracker = async { client.fetchDeviceTracker(settings) }
            override.await() to tracker.await()
        }
        if (overridden) return BedroomDecision.Allow(BedroomSkip.OVERRIDE_ACTIVE)

        val presence = LocationGate.evaluate(reading, LocationMode.SPECIFIC_ROOMS, settings.bedroomRooms)
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

    companion object {
        /** About the debounce window: long enough to absorb a launch's burst of events. */
        const val MEMORY_MILLIS = 5_000L
    }

    private fun covers(settings: AnchorSettings, packageName: String): Boolean =
        settings.bedroomBlockEnabled &&
            packageName != EveningGate.OWN_PACKAGE &&
            packageName in settings.blockedPackages
}
