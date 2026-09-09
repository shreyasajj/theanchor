package com.anchor.data.usage

/**
 * A foreground transition, normalised away from UsageStatsManager's event
 * constants so the calculator stays pure and testable.
 */
data class UsageEvent(
    val packageName: String,
    val type: Type,
    val timestampMillis: Long,
) {
    enum class Type { FOREGROUND, BACKGROUND }
}

/**
 * What the app did within a usage-day window.
 *
 * @param foregroundMillis time in the foreground within the window
 * @param opens launches within the window, coalescing brief re-entries
 * @param lastForegroundEndAtMillis end of the most recent *completed*
 *   session, considering events before the window too: a cooldown is a
 *   rolling gap and must survive the daily reset
 * @param currentSessionStartAtMillis non-null when the app is foreground now
 */
data class AppUsageSummary(
    val foregroundMillis: Long = 0,
    val opens: Int = 0,
    val lastForegroundEndAtMillis: Long? = null,
    val currentSessionStartAtMillis: Long? = null,
)
