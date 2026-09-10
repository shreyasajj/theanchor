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
 * An "open" is a run of one or more foreground sessions that belong
 * together: a quick re-entry (under a minute) or, when the app has a session
 * cap, any return while that cap's window is still running.
 *
 * @param foregroundMillis time in the foreground within the window
 * @param opens whole opens within the window, ignoring weights
 * @param openUnits opens weighted for limits: the first open after a
 *   voluntary early lock costs half
 * @param lastForegroundEndAtMillis end of the most recent *completed*
 *   session, considering events before the window too: a cooldown is a
 *   rolling gap and must survive the daily reset
 * @param currentSessionStartAtMillis non-null when the app is foreground now
 * @param lastOpenStartAtMillis when the most recent open began, whether or
 *   not it is still running; what a return would rejoin
 */
data class AppUsageSummary(
    val foregroundMillis: Long = 0,
    val opens: Int = 0,
    val openUnits: Double = 0.0,
    val lastForegroundEndAtMillis: Long? = null,
    val currentSessionStartAtMillis: Long? = null,
    val lastOpenStartAtMillis: Long? = null,
)
