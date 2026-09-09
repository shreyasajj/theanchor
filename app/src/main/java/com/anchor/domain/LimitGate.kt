package com.anchor.domain

import com.anchor.data.ha.KillSwitch
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.SettingsProvider
import com.anchor.data.usage.AppLimitDao
import com.anchor.data.usage.AppUsageSummary
import com.anchor.data.usage.UsageCalculator
import com.anchor.data.usage.UsageEventMapping
import com.anchor.data.usage.UsageStatsSource
import javax.inject.Inject
import javax.inject.Singleton

enum class LimitReason { DAILY_TIME, DAILY_OPENS, COOLDOWN, SESSION_CAP }

sealed interface LimitDecision {
    data object Allow : LimitDecision

    /** Show the pause screen for this many seconds, then let the app open. */
    data class Pause(val seconds: Int) : LimitDecision

    data class Blocked(val reason: LimitReason, val resetsAtMillis: Long) : LimitDecision
}

/**
 * Decides whether an app may be opened right now, given its usage limits.
 *
 * Runs *after* [ForegroundAppDecider] (so the emergency allowlist always
 * wins) and *before* [EveningGate] (so a spent budget is not something you
 * can answer three questions to get past).
 */
@Singleton
class LimitGate @Inject constructor(
    private val appLimitDao: AppLimitDao,
    private val usageStatsSource: UsageStatsSource,
    private val killSwitch: KillSwitch,
    private val anchorDate: AnchorDate,
    private val settingsProvider: SettingsProvider,
) {
    suspend fun decide(packageName: String): LimitDecision {
        val limit = appLimitDao.find(packageName)
        if (limit == null || !limit.enabled || !limit.hasAnyLimit) return LimitDecision.Allow

        val settings = settingsProvider()
        if (killSwitch.blockingDisabled(settings)) return LimitDecision.Allow

        val now = anchorDate.nowMillis()
        val summary = summarize(packageName, settings, now)
        val nextReset = anchorDate.nextUsageResetMillis(settings.dayResetMinute)

        // 1. Cooldown: the most immediate and most specific answer.
        limit.cooldownMinutes?.let { cooldown ->
            val lastEnd = summary.lastForegroundEndAtMillis
            if (lastEnd != null) {
                val availableAt = lastEnd + cooldown * 60_000L
                if (now < availableAt) {
                    return LimitDecision.Blocked(LimitReason.COOLDOWN, availableAt)
                }
            }
        }

        // 2. Open count. Strictly greater than: the launch being decided may
        //    not have reached UsageStatsManager yet, so this errs toward
        //    allowing by at most one open.
        limit.dailyOpens?.let { maxOpens ->
            if (summary.opens > maxOpens) {
                return LimitDecision.Blocked(LimitReason.DAILY_OPENS, nextReset)
            }
        }

        // 3. Time budget.
        limit.dailyMinutes?.let { maxMinutes ->
            if (summary.foregroundMillis >= maxMinutes * 60_000L) {
                return LimitDecision.Blocked(LimitReason.DAILY_TIME, nextReset)
            }
        }

        // 4. Nothing blocks, but make them wait first if configured.
        return if (limit.preOpenDelaySeconds > 0) {
            LimitDecision.Pause(limit.preOpenDelaySeconds)
        } else {
            LimitDecision.Allow
        }
    }

    /** Exposed for the dashboard and the session-cap watcher. */
    suspend fun summaryFor(packageName: String): AppUsageSummary =
        summarize(packageName, settingsProvider(), anchorDate.nowMillis())

    private suspend fun summarize(
        packageName: String,
        settings: AnchorSettings,
        nowMillis: Long,
    ): AppUsageSummary {
        val dayStart = anchorDate.usageDayStartMillis(settings.dayResetMinute)
        return UsageCalculator.summarize(
            events = usageStatsSource.events(UsageEventMapping.queryFrom(dayStart), nowMillis),
            packageName = packageName,
            windowStartMillis = dayStart,
            nowMillis = nowMillis,
        )
    }
}
