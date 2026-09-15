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
import com.anchor.data.usage.UsageEvent
import com.anchor.data.usage.UsageEventMapping
import com.anchor.data.usage.UsageStatsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

/** One limit's day, as the dashboard sees it. */
data class LimitUsage(
    val limit: AppLimit,
    val summary: AppUsageSummary,
    /** Each member's own foreground time and opens, for a group's breakdown. */
    val perApp: Map<String, AppUsageSummary>,
    val status: LimitStatus,
)

sealed interface LimitDecision {
    data object Allow : LimitDecision

    /**
     * Ask before the app opens: a countdown of this many seconds, or, at
     * zero, a plain question with Continue available at once. Every launch
     * of a limited app that is not a rejoin and not blocked is asked.
     */
    data class Pause(val seconds: Int) : LimitDecision

    data class Blocked(val reason: LimitReason, val resetsAtMillis: Long) : LimitDecision
}

/**
 * Where the limits come from. The accessibility service asks on every
 * foreground change, so the shipped implementation keeps them in memory and
 * refreshes from Room as they change; tests read the DAO directly.
 */
interface LimitLookup {
    /** Every limit containing the app; several when they have different windows. */
    suspend fun findAll(packageName: String): List<AppLimit>
    suspend fun all(): List<AppLimit>
}

class DaoLimitLookup(private val dao: AppLimitDao) : LimitLookup {
    override suspend fun findAll(packageName: String): List<AppLimit> = dao.findAll(packageName)
    override suspend fun all(): List<AppLimit> = dao.all()
}

/**
 * Mirrors the limit table into memory. Room's observer delivers every change,
 * so a lookup here is as current as one against the database and costs a
 * list scan instead of a query on the service's hot path.
 */
class CachedLimitLookup(dao: AppLimitDao, scope: CoroutineScope) : LimitLookup {
    private val limits = MutableStateFlow<List<AppLimit>?>(null)

    init {
        scope.launch { dao.observeAll().collect { limits.value = it } }
    }

    private suspend fun loaded(): List<AppLimit> = limits.value ?: limits.first { it != null }!!

    override suspend fun findAll(packageName: String): List<AppLimit> = loaded().filter { packageName in it }
    override suspend fun all(): List<AppLimit> = loaded()
}

/**
 * Decides whether an app may be opened right now, given its limits.
 *
 * Runs *after* [ForegroundAppDecider] (so the emergency allowlist always
 * wins) and *before* [EveningGate] (so a spent budget is not something you
 * can answer three questions to get past).
 */
@Singleton
class LimitGate @Inject constructor(
    private val limits: LimitLookup,
    private val earlyLockDao: EarlyLockDao,
    private val usageStatsSource: UsageStatsSource,
    private val killSwitch: KillSwitch,
    private val anchorDate: AnchorDate,
    private val settingsProvider: SettingsProvider,
) : SessionCapQueries {
    suspend fun decide(packageName: String): LimitDecision {
        val limit = limitFor(packageName)?.takeIf { it.isActive } ?: return LimitDecision.Allow

        val settings = settingsProvider()
        if (killSwitch.blockingDisabled(settings)) return LimitDecision.Allow
        if (isBypassed(limit, settings)) return LimitDecision.Allow

        val now = anchorDate.nowMillis()
        val usage = summarize(limit, settings, now)
        val summary = usage.summary
        val pauseOwed = limit.subject in settings.pausesOwed
        val nextReset = resetsAt(limit, settings)

        // 0. Coming back inside the session is the same open: it costs no
        //    second open, serves no cooldown and, since the pause was served
        //    on the way in, is not paused again. Only a pause that was walked
        //    away from is still owed.
        if (rejoinsOpen(limit, summary, now, pauseOwed, usage.lastEarlyLock)) {
            limit.effectiveDailyMinutes?.let { maxMinutes ->
                if (summary.foregroundMillis >= maxMinutes * 60_000L) {
                    return LimitDecision.Blocked(LimitReason.DAILY_TIME, nextReset)
                }
            }
            return LimitDecision.Allow
        }

        // 1. The daily budget: the longest wait, so it is reported first.
        //    Sitting out a cooldown only to be told the day is spent is the
        //    wrong order of bad news.
        spentBudget(limit, summary, nextReset)?.let { return it }

        // 2. Cooldown. An early lock counts as a close for this purpose.
        limit.cooldownMinutes?.let { cooldown ->
            val lastEnd = listOfNotNull(summary.lastForegroundEndAtMillis, usage.lastEarlyLock).maxOrNull()
            if (lastEnd != null) {
                val availableAt = lastEnd + cooldown * 60_000L
                if (now < availableAt) {
                    return LimitDecision.Blocked(LimitReason.COOLDOWN, availableAt)
                }
            }
        }

        // 3. Nothing blocks. Ask anyway: a countdown if one is set, a
        //    plain question if not. Opening a limited app is never silent.
        return LimitDecision.Pause(limit.preOpenDelaySeconds)
    }

    /**
     * A spent open count or time budget, whichever applies. The launch being
     * decided is a new open; if its own foreground event has already landed
     * it is the running open, and a running open never counts against itself.
     */
    private fun spentBudget(limit: AppLimit, summary: AppUsageSummary, resetsAt: Long): LimitDecision.Blocked? {
        limit.effectiveDailyOpens?.let { maxOpens ->
            if (unitsBeforeThisOpen(summary) >= maxOpens) return LimitDecision.Blocked(LimitReason.DAILY_OPENS, resetsAt)
        }
        limit.effectiveDailyMinutes?.let { maxMinutes ->
            if (summary.foregroundMillis >= maxMinutes * 60_000L) return LimitDecision.Blocked(LimitReason.DAILY_TIME, resetsAt)
        }
        return null
    }

    /**
     * What happens when the session ends on the app in front. The session is
     * how long until the user is asked again whether they want to be here,
     * so the answer is the pause, and Continue starts a new open. Unless that
     * new open cannot happen: the budget is spent, or a cooldown applies.
     * Also what an early lock does: it is the session ending by hand.
     */
    suspend fun afterSessionCap(packageName: String): LimitDecision {
        val now = anchorDate.nowMillis()
        val limit = limitFor(packageName)?.takeIf { it.isActive }
            ?: return LimitDecision.Pause(0)
        val settings = settingsProvider()
        val usage = summarize(limit, settings, now)
        val summary = usage.summary
        // Counting the open just ended: it is over, so it counts.
        spentBudget(limit, summary.copy(currentSessionStartAtMillis = null), resetsAt(limit, settings))
            ?.let { return it }
        limit.cooldownMinutes?.let { cooldown ->
            return LimitDecision.Blocked(LimitReason.SESSION_CAP, now + cooldown * 60_000L)
        }
        return LimitDecision.Pause(limit.preOpenDelaySeconds)
    }

    /**
     * Records that the user locked [packageName] early. The open in progress
     * ends here; going back in is a new open.
     */
    suspend fun lockEarly(packageName: String): Boolean {
        val limit = limitFor(packageName)?.takeIf { it.isActive } ?: return false
        val now = anchorDate.nowMillis()
        earlyLockDao.insert(EarlyLock(subject = limit.subject, atMillis = now))
        PauseLedger.ignoreAfterEarlyLock(limit.subject, now)
        return true
    }

    /** The limit in force for [packageName] right now, if any. */
    override suspend fun limitFor(packageName: String): AppLimit? {
        val minute = anchorDate.minuteOfDay()
        return limits.findAll(packageName).firstOrNull { it.appliesAt(minute) }
    }

    /**
     * Milliseconds of session left for the app in front, or null when it has
     * no session length, is bypassed, or the kill switch is on.
     */
    override suspend fun sessionRemainingMillis(packageName: String): Long? {
        val limit = limitFor(packageName)?.takeIf { it.isActive } ?: return null
        val cap = limit.sessionMinutes ?: return null
        val settings = settingsProvider()
        if (isBypassed(limit, settings) || killSwitch.blockingDisabled(settings)) return null
        val now = anchorDate.nowMillis()
        return SessionCapMath.remainingMillis(summarize(limit, settings, now).summary, cap, now)
    }

    /**
     * Whether the app, or any app sharing its limit, is in front according
     * to the usage log. The keyboard, the notification shade and our own
     * overlay do not move an app to the background there; another activity,
     * including the in-call screen, does.
     */
    override suspend fun isInForeground(packageName: String): Boolean =
        summaryFor(packageName).currentSessionStartAtMillis != null

    /** Exposed for the dashboard and tests. */
    suspend fun summaryFor(packageName: String): AppUsageSummary {
        val limit = limitFor(packageName) ?: AppLimit(packageName)
        return summarize(limit, settingsProvider(), anchorDate.nowMillis()).summary
    }

    /**
     * Usage of every active limit, from a single read of the event log. The
     * dashboard used to query the log once per app, which is what made it
     * slow to open.
     */
    suspend fun summaries(): Map<AppLimit, AppUsageSummary> {
        val settings = settingsProvider()
        val now = anchorDate.nowMillis()
        val dayStart = anchorDate.usageDayStartMillis(settings.dayResetMinute)
        val from = UsageEventMapping.queryFrom(dayStart)
        val active = limits.all().filter { it.isActive }
        if (active.isEmpty()) return emptyMap()
        val events = usageStatsSource.events(from, now)
        return active.associateWith { limit ->
            summarize(limit, settings, now, dayStart, events, earlyLockDao.since(limit.subject, from)).summary
        }
    }

    /**
     * Everything the dashboard shows for each active limit, from a single
     * read of the event log: the group's usage, its status, and each
     * member's own share.
     */
    suspend fun dashboardUsage(): List<LimitUsage> {
        val settings = settingsProvider()
        val now = anchorDate.nowMillis()
        val minute = anchorDate.minuteOfDay()
        val dayStart = anchorDate.usageDayStartMillis(settings.dayResetMinute)
        val from = UsageEventMapping.queryFrom(dayStart)
        val active = limits.all().filter { it.isActive }
        if (active.isEmpty()) return emptyList()
        val events = usageStatsSource.events(from, now)
        return active.map { limit ->
            val earlyLocks = earlyLockDao.since(limit.subject, from)
            val usage = summarize(limit, settings, now, dayStart, events, earlyLocks)
            val budgetStart = budgetStartMillis(limit, dayStart)
            LimitUsage(
                limit = limit,
                summary = usage.summary,
                perApp = limit.packages.associateWith { pkg ->
                    UsageCalculator.summarize(events, pkg, budgetStart, now)
                },
                status = LimitStatus.of(
                    limit = limit,
                    summary = usage.summary,
                    lastEarlyLock = usage.lastEarlyLock,
                    pauseOwed = limit.subject in settings.pausesOwed,
                    bypassed = isBypassed(limit, settings),
                    appliesNow = limit.appliesAt(minute),
                    nowMillis = now,
                    resetsAtMillis = resetsAt(limit, settings),
                ),
            )
        }
    }

    /**
     * What is left of this app's budget, for the pause and blocked screens.
     * Empty when the app is unlimited.
     */
    suspend fun budgetFor(packageName: String): List<String> {
        val limit = limitFor(packageName) ?: return emptyList()
        val now = anchorDate.nowMillis()
        return BudgetSummary.describe(limit, summarize(limit, settingsProvider(), now).summary, now)
    }

    /** When a spent budget comes back: the daily reset, or the end of the limit's window if sooner. */
    private fun resetsAt(limit: AppLimit, settings: AnchorSettings): Long {
        val daily = anchorDate.nextUsageResetMillis(settings.dayResetMinute)
        if (limit.isAllDay || !limit.appliesAt(anchorDate.minuteOfDay())) return daily
        return minOf(daily, anchorDate.windowEndMillis(limit.windowStartMinute!!, limit.windowEndMinute!!))
    }

    /**
     * Where this limit's budget starts counting: the usage day, or the start
     * of the limit's window if that is later. Limits with a window are only
     * consulted while inside it.
     */
    private fun budgetStartMillis(limit: AppLimit, dayStart: Long): Long {
        if (limit.isAllDay || !limit.appliesAt(anchorDate.minuteOfDay())) return dayStart
        return maxOf(dayStart, anchorDate.windowStartMillis(limit.windowStartMinute!!, limit.windowEndMinute!!))
    }

    private fun isBypassed(limit: AppLimit, settings: AnchorSettings): Boolean =
        Streak.isBypassed(settings.limitBypasses, limit.subject, anchorDate.usageDay(settings.dayResetMinute))

    /** Opens that are already over, and so count against a new one. */
    private fun unitsBeforeThisOpen(summary: AppUsageSummary): Double =
        if (summary.currentSessionStartAtMillis != null) summary.openUnits - summary.lastOpenUnits
        else summary.openUnits

    /**
     * True when a launch now would continue the previous open rather than
     * start a new one: the open began less than a session ago, it has run
     * long enough to be a real session (otherwise this launch *is* the open,
     * whose own foreground event may already be logged), it was not ended
     * early on purpose, and its pause was actually served.
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
        lastEarlyLock: Long?,
    ): Boolean {
        val window = limit.sessionMinutes?.let { it * 60_000L } ?: return false
        val openStart = summary.lastOpenStartAtMillis ?: return false
        if (pauseOwed || PauseLedger.hasUnfinishedPause(limit.subject)) return false
        if (now - openStart < MIN_ESTABLISHED_OPEN_MILLIS) return false
        if (!UsageCalculator.rejoins(now - openStart, window)) return false

        val lockedSince = lastEarlyLock?.let { it >= openStart } ?: false
        return !lockedSince
    }

    private class Usage(val summary: AppUsageSummary, val lastEarlyLock: Long?)

    private suspend fun summarize(limit: AppLimit, settings: AnchorSettings, nowMillis: Long): Usage {
        val dayStart = anchorDate.usageDayStartMillis(settings.dayResetMinute)
        val from = UsageEventMapping.queryFrom(dayStart)
        return summarize(
            limit, settings, nowMillis, dayStart,
            usageStatsSource.events(from, nowMillis),
            earlyLockDao.since(limit.subject, from),
        )
    }

    private fun summarize(
        limit: AppLimit,
        settings: AnchorSettings,
        nowMillis: Long,
        dayStart: Long,
        events: List<UsageEvent>,
        earlyLocks: List<Long>,
    ): Usage = Usage(
        summary = UsageCalculator.summarize(
            events = events,
            packageNames = limit.packages,
            windowStartMillis = budgetStartMillis(limit, dayStart),
            nowMillis = nowMillis,
            sessionWindowMillis = limit.sessionMinutes?.let { it * 60_000L },
            earlyLocksMillis = earlyLocks,
        ),
        lastEarlyLock = earlyLocks.lastOrNull(),
    )
}
