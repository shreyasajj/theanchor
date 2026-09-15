package com.anchor.domain

import com.anchor.data.db.DailyLogDao
import com.anchor.data.ha.HomeAssistantClient
import com.anchor.data.ha.KillSwitch
import com.anchor.data.ha.LocationGate
import com.anchor.data.ha.Presence
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.SettingsProvider
import javax.inject.Inject
import javax.inject.Singleton

sealed interface EveningDecision {
    /** Show the three-question overlay. */
    data object Strict : EveningDecision

    /** Show the 5-second interstitial: the fail-open path. */
    data object SimpleDelay : EveningDecision

    /** Let the app open untouched. */
    data class Allow(val reason: SkipReason) : EveningDecision
}

@Singleton
class EveningGate @Inject constructor(
    private val settingsProvider: SettingsProvider,
    private val dailyLogDao: DailyLogDao,
    private val client: HomeAssistantClient,
    private val killSwitch: KillSwitch,
    private val anchorDate: AnchorDate,
) {
    suspend fun decide(packageName: String): EveningDecision {
        val settings = settingsProvider()

        // Never intercept ourselves: that would be an infinite relaunch loop.
        if (packageName == OWN_PACKAGE) return EveningDecision.Allow(SkipReason.NOT_IN_SCOPE)
        if (packageName !in settings.blockedPackages) {
            return EveningDecision.Allow(SkipReason.NOT_IN_SCOPE)
        }
        return decideForTonight(settings)
    }

    /**
     * The questions on their own, with no app involved: when the window is
     * open, tonight is not done, and you are in scope. Off unless the user
     * turned [AnchorSettings.eveningPromptOnItsOwn] on. Only [EveningDecision.Strict]
     * means "ask"; a simple delay makes no sense without an app to delay.
     */
    suspend fun decideUnprompted(): EveningDecision {
        val settings = settingsProvider()
        if (!settings.eveningPromptOnItsOwn) return EveningDecision.Allow(SkipReason.NOT_IN_SCOPE)
        return decideForTonight(settings)
    }

    private suspend fun decideForTonight(settings: AnchorSettings): EveningDecision {
        if (!TimeWindow.contains(
                anchorDate.minuteOfDay(),
                settings.eveningStartMinute,
                settings.eveningEndMinute,
            )
        ) return EveningDecision.Allow(SkipReason.OUTSIDE_WINDOW)

        // Which night this belongs to: 01:00 still counts as the previous
        // evening, so answering at 22:00 keeps the block lifted until 05:00.
        val night = anchorDate.eveningAnchorDate(settings.eveningEndMinute)
        if (dailyLogDao.findByDate(night)?.eveningCompletedAt != null) {
            return EveningDecision.Allow(SkipReason.ALREADY_COMPLETED)
        }

        if (killSwitch.blockingDisabled(settings)) {
            return EveningDecision.Allow(SkipReason.OVERRIDE_ACTIVE)
        }

        val presence = LocationGate.evaluate(
            result = client.fetchDeviceTracker(settings),
            mode = settings.eveningLocationMode,
            allowedRooms = settings.eveningAllowedRooms,
        )
        return when (presence) {
            Presence.IN_SCOPE -> EveningDecision.Strict
            // A confirmed "away" always gets the lighter treatment.
            Presence.OUT_OF_SCOPE -> EveningDecision.SimpleDelay
            // "HA is down" does too, unless the user opted to ask anyway.
            Presence.UNKNOWN ->
                if (settings.enforceWithoutHomeAssistant) EveningDecision.Strict
                else EveningDecision.SimpleDelay
        }
    }

    companion object {
        const val OWN_PACKAGE = "com.anchor"
    }
}
