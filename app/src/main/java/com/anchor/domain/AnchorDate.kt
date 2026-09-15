package com.anchor.domain

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's only reader of "now". Everything else takes an AnchorDate.
 */
@Singleton
class AnchorDate @Inject constructor(private val clock: Clock) {

    private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    private fun now(): LocalDateTime = LocalDateTime.now(clock)

    fun nowMillis(): Long = clock.millis()

    fun today(): String = format(now().toLocalDate())

    fun minuteOfDay(): Int = now().toLocalTime().let { it.hour * 60 + it.minute }

    fun format(date: LocalDate): String = date.format(formatter)

    /**
     * Which calendar day an evening session belongs to. Times before the
     * evening window's end (e.g. 01:00 when the window ends at 05:00) belong
     * to the *previous* day, so the 10 PM and 1 AM halves of one night write
     * to the same [com.anchor.data.db.DailyLog] row.
     */
    fun eveningAnchorDate(eveningEndMinute: Int): String = dayAnchoredAt(eveningEndMinute)

    /**
     * Which usage day "now" falls in. Times before [dayResetMinute] belong to
     * the previous calendar day, so a session at 01:00 draws on the same
     * budget as the evening that preceded it.
     */
    fun usageDay(dayResetMinute: Int): String = dayAnchoredAt(dayResetMinute)

    /** The instant the current usage day began. */
    fun usageDayStartMillis(dayResetMinute: Int): Long =
        LocalDate.parse(usageDay(dayResetMinute))
            .atTime(dayResetMinute / 60, dayResetMinute % 60)
            .atZone(clock.zone)
            .toInstant()
            .toEpochMilli()

    /** The instant the current usage day ends and limits reset. */
    fun nextUsageResetMillis(dayResetMinute: Int): Long =
        LocalDate.parse(usageDay(dayResetMinute))
            .plusDays(1)
            .atTime(dayResetMinute / 60, dayResetMinute % 60)
            .atZone(clock.zone)
            .toInstant()
            .toEpochMilli()

    /**
     * When the current occurrence of a daily window began. Assumes now is
     * inside it: for a wrapping window (22:00-02:00) at 01:00 that is
     * yesterday at 22:00.
     */
    fun windowStartMillis(startMinute: Int, endMinute: Int): Long {
        val current = now()
        val date = if (TimeWindow.wraps(startMinute, endMinute) && minuteOfDay() < endMinute) {
            current.toLocalDate().minusDays(1)
        } else current.toLocalDate()
        return date.atTime(startMinute / 60, startMinute % 60).atZone(clock.zone).toInstant().toEpochMilli()
    }

    /** When the current occurrence of a daily window ends. Assumes now is inside it. */
    fun windowEndMillis(startMinute: Int, endMinute: Int): Long {
        val current = now()
        val date = if (TimeWindow.wraps(startMinute, endMinute) && minuteOfDay() >= startMinute) {
            current.toLocalDate().plusDays(1)
        } else current.toLocalDate()
        return date.atTime(endMinute / 60, endMinute % 60).atZone(clock.zone).toInstant().toEpochMilli()
    }

    private fun dayAnchoredAt(boundaryMinute: Int): String {
        val current = now()
        val minute = current.hour * 60 + current.minute
        val date = if (minute < boundaryMinute) {
            current.toLocalDate().minusDays(1)
        } else {
            current.toLocalDate()
        }
        return format(date)
    }
}
