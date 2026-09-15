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
 * What a limit's apps did within a usage-day window.
 *
 * An "open" is a run of one or more foreground sessions that belong
 * together: a quick re-entry (under a minute) or, when the limit has a
 * session length, any return while that session still has foreground time
 * left and the apps were left for less than the session length.
 *
 * @param foregroundMillis time in the foreground within the window
 * @param opens whole opens within the window, ignoring weights
 * @param openUnits opens as charged against the budget; today every open
 *   costs one, so this equals [opens]
 * @param lastOpenUnits what the most recent open cost, so a running open can
 *   be discounted when deciding whether a launch is over budget
 * @param lastForegroundEndAtMillis end of the most recent *completed*
 *   session, considering events before the window too: a cooldown is a
 *   rolling gap and must survive the daily reset
 * @param currentSessionStartAtMillis non-null when an app is foreground now
 * @param lastOpenStartAtMillis when the most recent open began, whether or
 *   not it is still running; what a return would rejoin
 * @param lastOpenEndAtMillis when the most recent open was last left, or
 *   null while it is running
 * @param currentOpenForegroundMillis foreground time inside the most recent
 *   open, up to now; the session clock
 */
data class AppUsageSummary(
    val foregroundMillis: Long = 0,
    val opens: Int = 0,
    val openUnits: Double = 0.0,
    val lastOpenUnits: Double = 0.0,
    val lastForegroundEndAtMillis: Long? = null,
    val currentSessionStartAtMillis: Long? = null,
    val lastOpenStartAtMillis: Long? = null,
    val lastOpenEndAtMillis: Long? = null,
    val currentOpenForegroundMillis: Long = 0,
)
