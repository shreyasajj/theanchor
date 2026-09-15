package com.anchor.domain

import com.anchor.data.usage.AppLimit
import com.anchor.data.usage.AppUsageSummary

/**
 * What is left of an app's budget, in words, for the pause and blocked
 * screens. Knowing the cost before you go in is the point of the pause.
 */
object BudgetSummary {

    /** Zero, one, two or three short lines; empty when the app has no budget. */
    fun describe(
        limit: AppLimit?,
        summary: AppUsageSummary,
        nowMillis: Long = 0,
    ): List<String> {
        if (limit == null || !limit.enabled) return emptyList()

        return buildList {
            limit.effectiveDailyMinutes?.let { total ->
                val used = (summary.foregroundMillis / 60_000L).toInt()
                add("${(total - used).coerceAtLeast(0)} of $total minutes left today")
            }
            limit.effectiveDailyOpens?.let { total ->
                val left = (total - summary.openUnits).coerceAtLeast(0.0)
                add("${formatOpens(left)} of $total opens left today")
            }
            limit.sessionMinutes?.let { cap ->
                val remaining = SessionCapMath.remainingMillis(summary, cap, nowMillis)
                if (remaining < cap * 60_000L) {
                    add("${(remaining / 60_000L).toInt()} of $cap minutes left in this session")
                } else {
                    add("$cap minutes per session")
                }
            }
        }
    }

    /** Halves read as "2.5", whole numbers as "2". */
    fun formatOpens(units: Double): String =
        if (units == units.toLong().toDouble()) units.toLong().toString() else "%.1f".format(units)
}
