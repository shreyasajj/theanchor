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
}
