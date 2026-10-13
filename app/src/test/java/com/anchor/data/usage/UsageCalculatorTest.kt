package com.anchor.data.usage

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UsageCalculatorTest {

    private val app = "com.google.android.youtube"
    private val instagram = "com.instagram.android"
    private val minute = 60_000L

    /** Day start at t=0 for readability; "now" supplied per test. */
    private val windowStart = 0L

    private fun fg(atMinutes: Long, pkg: String = app) = UsageEvent(pkg, UsageEvent.Type.FOREGROUND, atMinutes * minute)

    private fun bg(atMinutes: Long, pkg: String = app) = UsageEvent(pkg, UsageEvent.Type.BACKGROUND, atMinutes * minute)

    private fun summarize(events: List<UsageEvent>, nowMinutes: Long, opensAtMinutes: List<Long> = emptyList()) =
        UsageCalculator.summarize(events, app, windowStart, nowMinutes * minute, opensAtMinutes.map { it * minute })

    @Test
    fun `nothing at all means no usage`() {
        val s = summarize(emptyList(), nowMinutes = 60)

        assertThat(s.foregroundMillis).isEqualTo(0)
        assertThat(s.opens).isEqualTo(0)
        assertThat(s.lastForegroundEndAtMillis).isNull()
        assertThat(s.currentSessionStartAtMillis).isNull()
        assertThat(s.lastOpenStartAtMillis).isNull()
    }

    // --- The bug this replaces ---

    @Test
    fun `THE BUG - a flash closed from the pause is no open, no close, no session`() {
        // Launched at 10, covered by the pause 300ms later, closed from the pause.
        val flash = listOf(fg(10), UsageEvent(app, UsageEvent.Type.BACKGROUND, 10 * minute + 300))
        val s = summarize(flash, nowMinutes = 11)

        assertThat(s.opens).isEqualTo(0)
        assertThat(s.lastForegroundEndAtMillis).isNull()
        assertThat(s.lastOpenStartAtMillis).isNull()
        assertThat(s.foregroundMillis).isEqualTo(300)
    }

    @Test
    fun `the flash before Continue does not count as the close`() {
        val continuedAt = 10 * minute + 5_000
        val events = listOf(
            fg(10), UsageEvent(app, UsageEvent.Type.BACKGROUND, 10 * minute + 300),
            UsageEvent(app, UsageEvent.Type.FOREGROUND, continuedAt + 200), bg(20),
        )
        val s = UsageCalculator.summarize(events, app, windowStart, 30 * minute, listOf(continuedAt))

        assertThat(s.opens).isEqualTo(1)
        assertThat(s.lastOpenStartAtMillis).isEqualTo(continuedAt)
        assertThat(s.lastForegroundEndAtMillis).isEqualTo(20 * minute)
    }

    // --- Opens ---

    @Test
    fun `opens are the recorded opens from the window start`() {
        val s = summarize(emptyList(), nowMinutes = 60, opensAtMinutes = listOf(-30, 10, 20))

        assertThat(s.opens).isEqualTo(2)
        assertThat(s.openUnits).isEqualTo(2.0)
        assertThat(s.lastOpenStartAtMillis).isEqualTo(20 * minute)
    }

    @Test
    fun `an open before the window is not counted but still anchors the close`() {
        val s = summarize(listOf(fg(-40), bg(-30)), nowMinutes = 60, opensAtMinutes = listOf(-40))

        assertThat(s.opens).isEqualTo(0)
        assertThat(s.lastOpenStartAtMillis).isEqualTo(-40 * minute)
        assertThat(s.lastForegroundEndAtMillis).isEqualTo(-30 * minute)
    }

    @Test
    fun `sessions without opens are minutes, never opens`() {
        val s = summarize(listOf(fg(10), bg(20), fg(30), bg(35)), nowMinutes = 60)

        assertThat(s.opens).isEqualTo(0)
        assertThat(s.foregroundMillis).isEqualTo(15 * minute)
    }

    // --- The close after the open ---

    @Test
    fun `the close is where the open was left, not a later return`() {
        // Left at 20; back at 30 is past the grace, so it was paused: not this open.
        val s = summarize(listOf(fg(10), bg(20), fg(30), bg(35)), nowMinutes = 60, opensAtMinutes = listOf(10))
        assertThat(s.lastForegroundEndAtMillis).isEqualTo(20 * minute)
    }

    @Test
    fun `a session that ended before the latest open is not its close`() {
        val s = summarize(listOf(fg(10), bg(20)), nowMinutes = 60, opensAtMinutes = listOf(30))
        assertThat(s.lastForegroundEndAtMillis).isNull()
    }

    @Test
    fun `a running session does not overwrite the previous close`() {
        val s = summarize(listOf(fg(10), bg(20), fg(30)), nowMinutes = 40, opensAtMinutes = listOf(10))

        assertThat(s.lastForegroundEndAtMillis).isEqualTo(20 * minute)
        assertThat(s.currentSessionStartAtMillis).isEqualTo(30 * minute)
    }

    @Test
    fun `THE BUG, second launch - a later flash closed from the pause is not the close`() {
        // Opened at 600, left at 610. A launch at 650 was closed from the pause.
        val events = listOf(fg(600), bg(610), fg(650), UsageEvent(app, UsageEvent.Type.BACKGROUND, 650 * minute + 300))
        val s = summarize(events, nowMinutes = 660, opensAtMinutes = listOf(600))
        assertThat(s.lastForegroundEndAtMillis).isEqualTo(610 * minute)
    }

    @Test
    fun `a return within the grace is part of the open, so its end is the close`() {
        val back = 20 * minute + 30_000
        val events = listOf(fg(10), bg(20), UsageEvent(app, UsageEvent.Type.FOREGROUND, back), bg(35))
        val s = summarize(events, nowMinutes = 60, opensAtMinutes = listOf(10))
        assertThat(s.lastForegroundEndAtMillis).isEqualTo(35 * minute)
    }

    @Test
    fun `with a session length, returns inside the session are the open and later ones are not`() {
        val events = listOf(fg(10), bg(12), fg(17), bg(19), fg(25), bg(26))
        val s = UsageCalculator.summarize(
            events, app, windowStart, 60 * minute, listOf(10 * minute), sessionWindowMillis = 10 * minute,
        )
        assertThat(s.lastForegroundEndAtMillis).isEqualTo(19 * minute)
    }

    @Test
    fun `an early lock ends the open, so a launch after it is not its close`() {
        val events = listOf(fg(10), bg(12), fg(13), UsageEvent(app, UsageEvent.Type.BACKGROUND, 13 * minute + 300))
        val s = UsageCalculator.summarize(
            events, app, windowStart, 60 * minute, listOf(10 * minute),
            sessionWindowMillis = 10 * minute, earlyLocksMillis = listOf(12 * minute),
        )
        assertThat(s.lastForegroundEndAtMillis).isEqualTo(12 * minute)
    }

    // --- Minutes and in-front, from the log as before ---

    @Test
    fun `one closed session counts its duration`() {
        val s = summarize(listOf(fg(10), bg(25)), nowMinutes = 60)
        assertThat(s.foregroundMillis).isEqualTo(15 * minute)
        assertThat(s.currentSessionStartAtMillis).isNull()
    }

    @Test
    fun `a running session accrues time up to now`() {
        val s = summarize(listOf(fg(10)), nowMinutes = 40)
        assertThat(s.foregroundMillis).isEqualTo(30 * minute)
        assertThat(s.currentSessionStartAtMillis).isEqualTo(10 * minute)
    }

    @Test
    fun `a session spanning the window start is clamped to it`() {
        assertThat(summarize(listOf(fg(-20), bg(10)), nowMinutes = 60).foregroundMillis).isEqualTo(10 * minute)
    }

    @Test
    fun `a session entirely before the window contributes no time`() {
        assertThat(summarize(listOf(fg(-40), bg(-30)), nowMinutes = 60).foregroundMillis).isEqualTo(0)
    }

    @Test
    fun `a duplicate FOREGROUND event does not start a second session`() {
        assertThat(summarize(listOf(fg(10), fg(12), bg(20)), nowMinutes = 60).foregroundMillis).isEqualTo(10 * minute)
    }

    @Test
    fun `a BACKGROUND with no matching FOREGROUND is ignored`() {
        assertThat(summarize(listOf(bg(5), fg(10), bg(20)), nowMinutes = 60).foregroundMillis).isEqualTo(10 * minute)
    }

    @Test
    fun `events for other packages are ignored entirely`() {
        val s = summarize(listOf(fg(10), fg(11, instagram), bg(12, instagram), bg(20)), nowMinutes = 60)
        assertThat(s.foregroundMillis).isEqualTo(10 * minute)
    }

    @Test
    fun `unsorted input is handled`() {
        assertThat(summarize(listOf(bg(20), fg(10)), nowMinutes = 60).foregroundMillis).isEqualTo(10 * minute)
    }

    // --- Groups ---

    @Test
    fun `a group's apps are summed as one`() {
        val s = UsageCalculator.summarize(
            events = listOf(fg(10), bg(20), fg(20, instagram), bg(25, instagram)),
            packageNames = setOf(app, instagram), windowStartMillis = windowStart, nowMillis = 60 * minute,
            opensMillis = listOf(10 * minute),
        )

        assertThat(s.foregroundMillis).isEqualTo(15 * minute)
        assertThat(s.opens).isEqualTo(1)
        assertThat(s.lastForegroundEndAtMillis).isEqualTo(25 * minute)
    }

    @Test
    fun `a hand-over logged out of order does not double count`() {
        // Instagram's FOREGROUND lands a second before YouTube's BACKGROUND.
        val s = UsageCalculator.summarize(
            events = listOf(
                fg(10),
                UsageEvent(instagram, UsageEvent.Type.FOREGROUND, 13 * minute - 1_000),
                bg(13),
                bg(16, instagram),
            ),
            packageNames = setOf(app, instagram), windowStartMillis = windowStart, nowMillis = 60 * minute,
            opensMillis = emptyList(),
        )

        assertThat(s.foregroundMillis).isEqualTo(6 * minute)
    }
}
