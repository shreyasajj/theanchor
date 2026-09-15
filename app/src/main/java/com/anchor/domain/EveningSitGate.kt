package com.anchor.domain

import com.anchor.data.ha.HomeAssistantClient
import com.anchor.data.ha.KillSwitch
import com.anchor.data.ha.LocationGate
import com.anchor.data.ha.Presence
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.SettingsProvider
import com.anchor.data.usage.MeditationSessionDao
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SitDecision {
    /** Hold the phone until [requiredSeconds] of breathing have been recorded today. */
    data class Lock(val requiredSeconds: Int, val satSeconds: Int) : SitDecision
    data class Skip(val reason: SkipReason) : SitDecision
}

/** Pure part of the decision, so the arithmetic is testable without a database. */
object EveningSit {
    fun requiredSeconds(settings: AnchorSettings): Int = settings.eveningSitMinutes.coerceAtLeast(1) * 60

    fun isSatisfied(settings: AnchorSettings, satSecondsToday: Int): Boolean =
        satSecondsToday >= requiredSeconds(settings)
}

/**
 * Decides whether the evening sit lockdown should be up right now. Same
 * shape as [MorningGate]: cheapest checks first, the kill switch before any
 * network work, location last. Breathing done earlier in the day counts:
 * the requirement is "have sat today by the evening", not "sit now".
 */
@Singleton
class EveningSitGate @Inject constructor(
    private val settingsProvider: SettingsProvider,
    private val meditationDao: MeditationSessionDao,
    private val client: HomeAssistantClient,
    private val killSwitch: KillSwitch,
    private val anchorDate: AnchorDate,
) {
    suspend fun decide(): SitDecision {
        val settings = settingsProvider()
        if (!settings.eveningSitRequired) return SitDecision.Skip(SkipReason.NOT_IN_SCOPE)

        if (!TimeWindow.contains(
                anchorDate.minuteOfDay(),
                settings.eveningStartMinute,
                settings.eveningEndMinute,
            )
        ) return SitDecision.Skip(SkipReason.OUTSIDE_WINDOW)

        val sat = satSecondsToday(settings)
        if (EveningSit.isSatisfied(settings, sat)) return SitDecision.Skip(SkipReason.ALREADY_COMPLETED)

        if (killSwitch.blockingDisabled(settings)) return SitDecision.Skip(SkipReason.OVERRIDE_ACTIVE)

        val presence = LocationGate.evaluate(
            result = client.fetchDeviceTracker(settings),
            mode = settings.eveningLocationMode,
            allowedRooms = settings.eveningAllowedRooms,
        )
        val lock = SitDecision.Lock(EveningSit.requiredSeconds(settings), sat)
        return when (presence) {
            Presence.IN_SCOPE -> lock
            Presence.OUT_OF_SCOPE -> SitDecision.Skip(SkipReason.NOT_IN_SCOPE)
            Presence.UNKNOWN ->
                if (settings.enforceWithoutHomeAssistant) lock
                else SitDecision.Skip(SkipReason.LOCATION_UNKNOWN)
        }
    }

    /** Seconds of breathing recorded since the usage day began. */
    suspend fun satSecondsToday(settings: AnchorSettings): Int =
        meditationDao.secondsSince(anchorDate.usageDayStartMillis(settings.dayResetMinute))
}
