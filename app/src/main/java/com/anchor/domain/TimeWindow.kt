package com.anchor.domain

/**
 * Half-open [start, end) windows over minutes-since-midnight, supporting
 * windows that cross midnight (the evening window is typically 20:00–05:00).
 */
object TimeWindow {

    /** True when the window crosses midnight, i.e. start is later than end. */
    fun wraps(startMinute: Int, endMinute: Int): Boolean = startMinute > endMinute

    fun contains(minuteOfDay: Int, startMinute: Int, endMinute: Int): Boolean = when {
        startMinute == endMinute -> false                       // zero-length
        wraps(startMinute, endMinute) ->                        // e.g. 20:00–05:00
            minuteOfDay >= startMinute || minuteOfDay < endMinute
        else ->                                                 // e.g. 05:00–12:00
            minuteOfDay in startMinute until endMinute
    }

    /** True when the two windows share at least one minute. Zero-length windows overlap nothing. */
    fun overlaps(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Boolean {
        if (aStart == aEnd || bStart == bEnd) return false
        return segments(aStart, aEnd).any { (s1, e1) ->
            segments(bStart, bEnd).any { (s2, e2) -> s1 < e2 && s2 < e1 }
        }
    }

    /** A window as one or two non-wrapping [start, end) segments within the day. */
    private fun segments(start: Int, end: Int): List<Pair<Int, Int>> =
        if (wraps(start, end)) listOf(start to MINUTES_PER_DAY, 0 to end) else listOf(start to end)

    const val MINUTES_PER_DAY = 24 * 60
}
