package com.anchor.domain

import com.anchor.data.usage.AppLimit
import com.anchor.data.usage.AppUsageSummary
import com.anchor.data.usage.UsageCalculator

/**
 * Where a limit stands right now, for the dashboard. Follows the same order
 * of checks as [LimitGate.decide], so what the dashboard says matches what
 * the next launch would do: a session is a session before a spent budget,
 * and a spent budget is reported before a cooldown.
 */
sealed interface LimitStatus {
    /** Walked through in streak mode; nothing applies until the reset. */
    data object Waived : LimitStatus

    /** The limit has hours and now is outside them. */
    data object OffHours : LimitStatus

    /**
     * An open is running by the clock. [endsAtMillis] is null for a limit
     * with no session length, where "in session" just means "in front".
     */
    data class InSession(val endsAtMillis: Long?, val inForeground: Boolean) : LimitStatus

    data class Spent(val resetsAtMillis: Long) : LimitStatus

    data class Cooldown(val untilMillis: Long) : LimitStatus

    /** The next launch would go straight in (or through the pause). */
    data object Available : LimitStatus

    companion object {
        fun of(
            limit: AppLimit,
            summary: AppUsageSummary,
            lastEarlyLock: Long?,
            pauseOwed: Boolean,
            bypassed: Boolean,
            appliesNow: Boolean,
            nowMillis: Long,
            resetsAtMillis: Long,
        ): LimitStatus {
            if (bypassed) return Waived
            if (!appliesNow) return OffHours

            val timeSpent = limit.effectiveDailyMinutes?.let { summary.foregroundMillis >= it * 60_000L } ?: false
            val inFront = summary.currentSessionStartAtMillis != null

            val sessionEnd = sessionEndsAt(limit, summary, lastEarlyLock, pauseOwed, nowMillis)
            if (sessionEnd != null) {
                return if (timeSpent) Spent(resetsAtMillis) else InSession(sessionEnd, inFront)
            }

            val finishedUnits = if (inFront) summary.openUnits - summary.lastOpenUnits else summary.openUnits
            val opensSpent = limit.effectiveDailyOpens?.let { finishedUnits >= it } ?: false
            if (opensSpent || timeSpent) return Spent(resetsAtMillis)

            if (inFront) return InSession(endsAtMillis = null, inForeground = true)

            limit.cooldownMinutes?.let { cooldown ->
                val lastEnd = listOfNotNull(summary.lastForegroundEndAtMillis, lastEarlyLock).maxOrNull()
                if (lastEnd != null) {
                    val until = lastEnd + cooldown * 60_000L
                    if (nowMillis < until) return Cooldown(until)
                }
            }
            return Available
        }

        /** When the running session ends, or null when a launch now would be a new open. */
        private fun sessionEndsAt(
            limit: AppLimit,
            summary: AppUsageSummary,
            lastEarlyLock: Long?,
            pauseOwed: Boolean,
            nowMillis: Long,
        ): Long? {
            val window = limit.sessionMinutes?.let { it * 60_000L } ?: return null
            val start = summary.lastOpenStartAtMillis ?: return null
            if (pauseOwed || PauseLedger.hasUnfinishedPause(limit.subject)) return null
            if (lastEarlyLock != null && lastEarlyLock >= start) return null
            if (!UsageCalculator.rejoins(nowMillis - start, window)) return null
            return start + window
        }
    }
}
