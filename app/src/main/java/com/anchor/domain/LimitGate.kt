package com.anchor.domain

import com.anchor.data.ha.KillSwitch
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.SettingsProvider
import com.anchor.data.usage.AppLimit
import com.anchor.data.usage.AppLimitDao
import com.anchor.data.usage.AppUsageSummary
import com.anchor.data.usage.EarlyLock
import com.anchor.data.usage.EarlyLockDao
import com.anchor.data.usage.UsageCalculator
import com.anchor.data.usage.UsageEventMapping
import com.anchor.data.usage.UsageStatsSource
import javax.inject.Inject
import javax.inject.Singleton

enum class LimitReason { DAILY_TIME, DAILY_OPENS, COOLDOWN, SESSION_CAP }

/**
 * How long an open must have been running before returning to it counts as
 * rejoining rather than opening afresh.
 *
 * Launching an app emits a foreground event and then, a fraction of a second
 * later, a background one: a splash handing over to the real activity, or the
 * launcher animation settling. Showing the pause screen does the same. Any of
 * those look identical to "the user left", so without a floor the very first
 * launch of an app reads as a return to an open that is 200ms old, and the
 * pause is skipped for good.
 */
const val MIN_ESTABLISHED_OPEN_MILLIS = 15_000L

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
    private val earlyLockDao: EarlyLockDao,
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
        val summary = summarize(packageName, limit, settings, now)
        val pauseOwed = packageName in settings.pausesOwed
        val nextReset = anchorDate.nextUsageResetMillis(settings.dayResetMinute)


        // 0. Coming back inside the session window is the same open, so it
        //    costs no second open and serves no cooldown. The pause is a
        //    different matter: it is friction on *entering* the app, and
        //    coming back is entering, so it still applies. Skipping it here
        //    was what let a walked-away-from pause never return.
        if (rejoinsOpen(limit, summary, now, pauseOwed)) {
            limit.dailyMinutes?.let { maxMinutes ->
                if (summary.foregroundMillis >= maxMinutes * 60_000L) {
                    return LimitDecision.Blocked(LimitReason.DAILY_TIME, nextReset)
                }
            }
            return if (limit.preOpenDelaySeconds > 0) {
                LimitDecision.Pause(limit.preOpenDelaySeconds)
            } else {
                LimitDecision.Allow
            }
        }

        // 1. Cooldown: the most immediate and most specific answer. An early
        //    lock counts as a close for this purpose.
        limit.cooldownMinutes?.let { cooldown ->
            val lastEnd = listOfNotNull(summary.lastForegroundEndAtMillis, lastEarlyLock).maxOrNull()
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
            if (summary.openUnits > maxOpens) {
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

    /**
     * Records that the user locked [packageName] early. The open in progress
     * ends here; the next one costs half.
     */
    suspend fun lockEarly(packageName: String): Boolean {
        val limit = appLimitDao.find(packageName)?.takeIf { it.enabled && it.hasAnyLimit } ?: return false
        earlyLockDao.insert(EarlyLock(packageName = packageName, atMillis = anchorDate.nowMillis()))
        return true
    }

    /** Exposed for the dashboard and the session-cap watcher. */
    suspend fun summaryFor(packageName: String): AppUsageSummary {
        val limit = appLimitDao.find(packageName)
        return summarize(packageName, limit, settingsProvider(), anchorDate.nowMillis())
    }

    /**
     * What is left of this app's budget, for the pause and blocked screens.
     * Null limit means the app is unlimited.
     */
    suspend fun budgetFor(packageName: String): List<String> {
        val limit = appLimitDao.find(packageName) ?: return emptyList()
        val now = anchorDate.nowMillis()
        val summary = summarize(packageName, limit, settingsProvider(), now)
        return BudgetSummary.describe(limit, summary, now)
    }

    /** The most recent early lock seen by the last [summarize] call. */
    private var lastEarlyLock: Long? = null

    /**
     * True when a launch now would continue the previous open rather than
     * start a new one: the open began inside the session window, the user has
     * left it at least once since (otherwise this launch *is* the open, whose
     * own foreground event may already be logged), it was not ended early on
     * purpose, and its pause was actually served.
     *
     * That last condition matters more than it looks. Showing the pause screen
     * pushes the app into the background, which logs exactly the same
     * BACKGROUND event as genuinely leaving. Without it, opening an app,
     * walking away from the pause and coming back reads as a legitimate
     * rejoin, and the pause is skipped entirely.
     */
    private fun rejoinsOpen(
        limit: AppLimit,
        summary: AppUsageSummary,
        now: Long,
        pauseOwed: Boolean,
    ): Boolean {
        val window = limit.sessionMinutes?.let { it * 60_000L } ?: return false
        val openStart = summary.lastOpenStartAtMillis ?: return false
        if (pauseOwed || PauseLedger.hasUnfinishedPause(limit.packageName)) return false

        val age = now - openStart
        // Old enough to be a real session, and still inside the cap's window.
        if (age < MIN_ESTABLISHED_OPEN_MILLIS || age >= window) return false

        val leftSince = summary.lastForegroundEndAtMillis?.let { it >= openStart } ?: false
        val lockedSince = lastEarlyLock?.let { it >= openStart } ?: false
        return leftSince && !lockedSince
    }

    private suspend fun summarize(
        packageName: String,
        limit: AppLimit?,
        settings: AnchorSettings,
        nowMillis: Long,
    ): AppUsageSummary {
        val dayStart = anchorDate.usageDayStartMillis(settings.dayResetMinute)
        val from = UsageEventMapping.queryFrom(dayStart)
        val earlyLocks = earlyLockDao.since(packageName, from)
        lastEarlyLock = earlyLocks.lastOrNull()
        return UsageCalculator.summarize(
            events = usageStatsSource.events(from, nowMillis),
            packageName = packageName,
            windowStartMillis = dayStart,
            nowMillis = nowMillis,
            sessionWindowMillis = limit?.sessionMinutes?.let { it * 60_000L },
            earlyLocksMillis = earlyLocks,
        )
    }
}
