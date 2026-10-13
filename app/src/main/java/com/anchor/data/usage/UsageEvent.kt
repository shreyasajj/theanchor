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
 * What a limit's apps did within a usage-day window. Opens come from the
 * recorded opens; time and "in front" come from the usage log.
 *
 * @param foregroundMillis time in the foreground within the window
 * @param opens recorded opens within the window
 * @param openUnits opens as charged against the budget; every open costs one
 * @param lastForegroundEndAtMillis end of the last completed session after
 *   the latest open, considering opens before the window too: a cooldown is
 *   a rolling gap and must survive the daily reset. Null when nothing was
 *   left since the latest open, so a flash closed from the pause starts no
 *   cooldown.
 * @param currentSessionStartAtMillis non-null when an app is foreground now
 * @param lastOpenStartAtMillis when the latest open was recorded; the
 *   session clock runs from here
 */
data class AppUsageSummary(
    val foregroundMillis: Long = 0,
    val opens: Int = 0,
    val openUnits: Double = 0.0,
    val lastForegroundEndAtMillis: Long? = null,
    val currentSessionStartAtMillis: Long? = null,
    val lastOpenStartAtMillis: Long? = null,
)
