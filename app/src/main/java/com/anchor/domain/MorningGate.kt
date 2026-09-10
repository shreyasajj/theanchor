package com.anchor.domain

import com.anchor.data.db.DailyLogDao
import com.anchor.data.ha.HomeAssistantClient
import com.anchor.data.ha.KillSwitch
import com.anchor.data.ha.LocationGate
import com.anchor.data.ha.Presence
import com.anchor.data.settings.SettingsProvider
import javax.inject.Inject
import javax.inject.Singleton

enum class SkipReason {
    OUTSIDE_WINDOW,
    ALREADY_COMPLETED,
    OVERRIDE_ACTIVE,
    NOT_IN_SCOPE,
    LOCATION_UNKNOWN,
}

sealed interface MorningDecision {
    data object Lock : MorningDecision
    data class Skip(val reason: SkipReason) : MorningDecision
}

/**
 * Decides whether the morning lockdown should fire right now.
 *
 * Order of checks, cheapest and most restrictive first:
 *  1. inside the window?  2. already done today?  3. kill switch?  4. location.
 * The kill switch is checked before the location so an override short-circuits
 * network work, and it is read at decision time, never cached.
 */
@Singleton
class MorningGate @Inject constructor(
    private val settingsProvider: SettingsProvider,
    private val dailyLogDao: DailyLogDao,
    private val client: HomeAssistantClient,
    private val killSwitch: KillSwitch,
    private val anchorDate: AnchorDate,
) {
    suspend fun decide(): MorningDecision {
        val settings = settingsProvider()

        if (!TimeWindow.contains(
                anchorDate.minuteOfDay(),
                settings.morningStartMinute,
                settings.morningEndMinute,
            )
        ) return MorningDecision.Skip(SkipReason.OUTSIDE_WINDOW)

        val today = anchorDate.today()
        if (dailyLogDao.findByDate(today)?.morningCompletedAt != null) {
            return MorningDecision.Skip(SkipReason.ALREADY_COMPLETED)
        }

        if (killSwitch.blockingDisabled(settings)) {
            return MorningDecision.Skip(SkipReason.OVERRIDE_ACTIVE)
        }

        // Fail-open: anything short of a confirmed in-scope reading skips.
        val presence = LocationGate.evaluate(
            result = client.fetchDeviceTracker(settings),
            mode = settings.morningLocationMode,
            allowedRooms = settings.morningAllowedRooms,
        )
        return when (presence) {
            Presence.IN_SCOPE -> MorningDecision.Lock
            Presence.OUT_OF_SCOPE -> MorningDecision.Skip(SkipReason.NOT_IN_SCOPE)
            Presence.UNKNOWN ->
                if (settings.enforceWithoutHomeAssistant) MorningDecision.Lock
                else MorningDecision.Skip(SkipReason.LOCATION_UNKNOWN)
        }
    }
}
