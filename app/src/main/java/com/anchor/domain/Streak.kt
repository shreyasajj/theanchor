package com.anchor.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Streak arithmetic. A streak is the run of usage days, ending today, on
 * which the user did not walk through a limit. Nothing is counted per day;
 * only the day the current run began is stored.
 */
object Streak {

    /** Days in the run that began on [startDay], counting [today]. Zero before it begins. */
    fun count(startDay: String?, today: String): Int {
        if (startDay == null) return 0
        val days = ChronoUnit.DAYS.between(LocalDate.parse(startDay), LocalDate.parse(today))
        return (days + 1).coerceAtLeast(0).toInt()
    }

    /** After a break on [day] the run restarts the day after, so [day] counts for nothing. */
    fun startAfterBreak(day: String): String = LocalDate.parse(day).plusDays(1).toString()

    fun bypassKey(subject: String, usageDay: String): String = "$subject|$usageDay"

    fun isBypassed(bypasses: Set<String>, subject: String, usageDay: String): Boolean =
        bypassKey(subject, usageDay) in bypasses
}
