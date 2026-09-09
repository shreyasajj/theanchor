package com.anchor.data.usage

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UsageCalculatorTest {

    private val app = "com.google.android.youtube"
    private val minute = 60_000L

    /** Day start at t=0 for readability; "now" supplied per test. */
    private val windowStart = 0L

    private fun fg(atMinutes: Long, pkg: String = app) = UsageEvent(pkg, UsageEvent.Type.FOREGROUND, atMinutes * minute)

    private fun bg(atMinutes: Long, pkg: String = app) = UsageEvent(pkg, UsageEvent.Type.BACKGROUND, atMinutes * minute)

    private fun summarize(events: List<UsageEvent>, nowMinutes: Long) =
        UsageCalculator.summarize(events, app, windowStart, nowMinutes * minute)

    @Test
    fun `no events means no usage`() {
        val s = summarize(emptyList(), nowMinutes = 60)

        assertThat(s.foregroundMillis).isEqualTo(0)
        assertThat(s.opens).isEqualTo(0)
        assertThat(s.lastForegroundEndAtMillis).isNull()
        assertThat(s.currentSessionStartAtMillis).isNull()
    }

    @Test
    fun `one closed session counts its duration and one open`() {
        val s = summarize(listOf(fg(10), bg(25)), nowMinutes = 60)

        assertThat(s.foregroundMillis).isEqualTo(15 * minute)
        assertThat(s.opens).isEqualTo(1)
        assertThat(s.lastForegroundEndAtMillis).isEqualTo(25 * minute)
        assertThat(s.currentSessionStartAtMillis).isNull()
    }

    @Test
    fun `an open session accrues time up to now`() {
        val s = summarize(listOf(fg(10)), nowMinutes = 40)

        assertThat(s.foregroundMillis).isEqualTo(30 * minute)
        assertThat(s.opens).isEqualTo(1)
        assertThat(s.currentSessionStartAtMillis).isEqualTo(10 * minute)
        assertThat(s.lastForegroundEndAtMillis).isNull()
    }

    @Test
    fun `a session spanning the window start is clamped to it`() {
        val s = UsageCalculator.summarize(
            events = listOf(fg(-20), bg(10)),
            packageName = app,
            windowStartMillis = 0L,
            nowMillis = 60 * minute,
        )

        assertThat(s.foregroundMillis).isEqualTo(10 * minute)
    }

    @Test
    fun `a session entirely before the window contributes no time or opens`() {
        val s = UsageCalculator.summarize(
            events = listOf(fg(-40), bg(-30)),
            packageName = app,
            windowStartMillis = 0L,
            nowMillis = 60 * minute,
        )

        assertThat(s.foregroundMillis).isEqualTo(0)
        assertThat(s.opens).isEqualTo(0)
    }

    @Test
    fun `but a session before the window still sets the last close time`() {
        val s = UsageCalculator.summarize(
            events = listOf(fg(-40), bg(-30)),
            packageName = app,
            windowStartMillis = 0L,
            nowMillis = 60 * minute,
        )

        assertThat(s.lastForegroundEndAtMillis).isEqualTo(-30 * minute)
    }

    @Test
    fun `a duplicate FOREGROUND event does not start a second session`() {
        val s = summarize(listOf(fg(10), fg(12), bg(20)), nowMinutes = 60)

        assertThat(s.foregroundMillis).isEqualTo(10 * minute)
        assertThat(s.opens).isEqualTo(1)
    }

    @Test
    fun `a BACKGROUND with no matching FOREGROUND is ignored`() {
        val s = summarize(listOf(bg(5), fg(10), bg(20)), nowMinutes = 60)

        assertThat(s.foregroundMillis).isEqualTo(10 * minute)
        assertThat(s.opens).isEqualTo(1)
    }

    @Test
    fun `two sessions less than a minute apart count as one open`() {
        val s = UsageCalculator.summarize(
            events = listOf(
                fg(10), bg(20),
                UsageEvent(app, UsageEvent.Type.FOREGROUND, 20 * minute + 15_000),
                UsageEvent(app, UsageEvent.Type.BACKGROUND, 30 * minute),
            ),
            packageName = app,
            windowStartMillis = windowStart,
            nowMillis = 60 * minute,
        )

        assertThat(s.opens).isEqualTo(1)
        assertThat(s.foregroundMillis).isEqualTo(10 * minute + (10 * minute - 15_000))
    }

    @Test
    fun `two sessions more than a minute apart count as two opens`() {
        val s = summarize(listOf(fg(10), bg(20), fg(30), bg(35)), nowMinutes = 60)

        assertThat(s.opens).isEqualTo(2)
        assertThat(s.foregroundMillis).isEqualTo(15 * minute)
    }

    @Test
    fun `exactly the coalesce window apart counts as a new open`() {
        val s = UsageCalculator.summarize(
            events = listOf(
                fg(10), bg(20),
                UsageEvent(app, UsageEvent.Type.FOREGROUND, 20 * minute + 60_000),
            ),
            packageName = app,
            windowStartMillis = windowStart,
            nowMillis = 60 * minute,
        )

        assertThat(s.opens).isEqualTo(2)
    }

    @Test
    fun `events for other packages are ignored entirely`() {
        val s = summarize(
            listOf(fg(10), fg(11, "com.instagram.android"), bg(12, "com.instagram.android"), bg(20)),
            nowMinutes = 60,
        )

        assertThat(s.foregroundMillis).isEqualTo(10 * minute)
        assertThat(s.opens).isEqualTo(1)
    }

    @Test
    fun `unsorted input is handled`() {
        val s = summarize(listOf(bg(20), fg(10)), nowMinutes = 60)

        assertThat(s.foregroundMillis).isEqualTo(10 * minute)
        assertThat(s.opens).isEqualTo(1)
    }

    @Test
    fun `lastForegroundEndAt reports the most recent completed session`() {
        val s = summarize(listOf(fg(10), bg(20), fg(30), bg(35)), nowMinutes = 60)
        assertThat(s.lastForegroundEndAtMillis).isEqualTo(35 * minute)
    }

    @Test
    fun `an open session does not overwrite the previous close time`() {
        val s = summarize(listOf(fg(10), bg(20), fg(30)), nowMinutes = 40)

        assertThat(s.lastForegroundEndAtMillis).isEqualTo(20 * minute)
        assertThat(s.currentSessionStartAtMillis).isEqualTo(30 * minute)
    }
}
