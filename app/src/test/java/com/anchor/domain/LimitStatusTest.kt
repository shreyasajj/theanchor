package com.anchor.domain

import com.anchor.data.usage.AppLimit
import com.anchor.data.usage.AppUsageSummary
import com.anchor.data.usage.LimitMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LimitStatusTest {

    private val minute = 60_000L
    private val now = 1_000_000_000L
    private val reset = now + 60 * minute

    private fun status(
        limit: AppLimit,
        summary: AppUsageSummary = AppUsageSummary(),
        lastEarlyLock: Long? = null,
        pauseOwed: Boolean = false,
        bypassed: Boolean = false,
        appliesNow: Boolean = true,
    ) = LimitStatus.of(limit, summary, lastEarlyLock, pauseOwed, bypassed, appliesNow, now, reset)

    private val opens = AppLimit("x", dailyOpens = 2, sessionMinutes = 10, cooldownMinutes = 5)

    @Test
    fun `waived outranks everything`() {
        val spent = AppUsageSummary(opens = 2, openUnits = 2.0)
        assertThat(status(opens, spent, bypassed = true)).isEqualTo(LimitStatus.Waived)
    }

    @Test
    fun `off hours when the limit does not apply now`() {
        assertThat(status(opens, appliesNow = false)).isEqualTo(LimitStatus.OffHours)
    }

    @Test
    fun `available when nothing has happened`() {
        assertThat(status(opens)).isEqualTo(LimitStatus.Available)
    }

    @Test
    fun `in session while the open is younger than the session length`() {
        val start = now - 4 * minute
        val running = AppUsageSummary(
            opens = 1, openUnits = 1.0, lastOpenUnits = 1.0,
            lastOpenStartAtMillis = start, currentSessionStartAtMillis = start,
        )
        assertThat(status(opens, running))
            .isEqualTo(LimitStatus.InSession(endsAtMillis = start + 10 * minute, inForeground = true))
    }

    @Test
    fun `a session left is still a session until it elapses`() {
        val start = now - 4 * minute
        val left = AppUsageSummary(
            opens = 1, openUnits = 1.0, lastOpenUnits = 1.0,
            lastOpenStartAtMillis = start, lastOpenEndAtMillis = now - minute,
            lastForegroundEndAtMillis = now - minute,
        )
        assertThat(status(opens, left))
            .isEqualTo(LimitStatus.InSession(endsAtMillis = start + 10 * minute, inForeground = false))
    }

    @Test
    fun `an early lock ends the session`() {
        val start = now - 4 * minute
        val left = AppUsageSummary(
            opens = 1, openUnits = 1.0, lastOpenUnits = 1.0,
            lastOpenStartAtMillis = start, lastForegroundEndAtMillis = now - minute,
        )
        assertThat(status(opens, left, lastEarlyLock = now - minute))
            .isEqualTo(LimitStatus.Cooldown(untilMillis = now - minute + 5 * minute))
    }

    @Test
    fun `an owed pause is not a session`() {
        val start = now - 4 * minute
        val left = AppUsageSummary(opens = 1, openUnits = 1.0, lastOpenStartAtMillis = start)
        assertThat(status(opens, left, pauseOwed = true)).isEqualTo(LimitStatus.Available)
    }

    @Test
    fun `spent when finished opens reach the budget`() {
        val done = AppUsageSummary(opens = 2, openUnits = 2.0, lastOpenUnits = 1.0, lastOpenStartAtMillis = now - 30 * minute)
        assertThat(status(opens, done)).isEqualTo(LimitStatus.Spent(resetsAtMillis = reset))
    }

    @Test
    fun `the running open does not count against itself`() {
        // Second open in progress, but its session has elapsed: it is still
        // the second open, so the day is not spent; it is simply in use.
        val start = now - 30 * minute
        val running = AppUsageSummary(
            opens = 2, openUnits = 2.0, lastOpenUnits = 1.0,
            lastOpenStartAtMillis = start, currentSessionStartAtMillis = start,
        )
        assertThat(status(opens, running)).isEqualTo(LimitStatus.InSession(endsAtMillis = null, inForeground = true))
    }

    @Test
    fun `spent time budget wins even inside a session`() {
        val time = AppLimit("x", limitMode = LimitMode.TIME, dailyMinutes = 20, sessionMinutes = 10)
        val start = now - 2 * minute
        val running = AppUsageSummary(
            foregroundMillis = 20 * minute, lastOpenStartAtMillis = start, currentSessionStartAtMillis = start,
        )
        assertThat(status(time, running)).isEqualTo(LimitStatus.Spent(resetsAtMillis = reset))
    }

    @Test
    fun `cooldown after closing`() {
        val closed = AppUsageSummary(
            opens = 1, openUnits = 1.0, lastOpenUnits = 1.0,
            lastOpenStartAtMillis = now - 20 * minute, lastForegroundEndAtMillis = now - 2 * minute,
        )
        assertThat(status(opens, closed)).isEqualTo(LimitStatus.Cooldown(untilMillis = now + 3 * minute))
    }

    @Test
    fun `available once the cooldown has passed`() {
        val closed = AppUsageSummary(
            opens = 1, openUnits = 1.0, lastOpenUnits = 1.0,
            lastOpenStartAtMillis = now - 20 * minute, lastForegroundEndAtMillis = now - 6 * minute,
        )
        assertThat(status(opens, closed)).isEqualTo(LimitStatus.Available)
    }

    @Test
    fun `an app with no session length in front now is in use`() {
        val plain = AppLimit("x", dailyOpens = 3)
        val running = AppUsageSummary(opens = 1, openUnits = 1.0, lastOpenUnits = 1.0, currentSessionStartAtMillis = now - minute)
        assertThat(status(plain, running)).isEqualTo(LimitStatus.InSession(endsAtMillis = null, inForeground = true))
    }
}
