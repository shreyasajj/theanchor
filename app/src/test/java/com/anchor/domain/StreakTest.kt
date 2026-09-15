package com.anchor.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreakTest {

    @Test
    fun `no start day means no streak`() {
        assertThat(Streak.count(null, "2026-09-14")).isEqualTo(0)
    }

    @Test
    fun `the first day counts as one`() {
        assertThat(Streak.count("2026-09-14", "2026-09-14")).isEqualTo(1)
    }

    @Test
    fun `days are counted inclusively`() {
        assertThat(Streak.count("2026-09-10", "2026-09-14")).isEqualTo(5)
    }

    @Test
    fun `a start day in the future is zero, not negative`() {
        // The day of a break: the streak restarts tomorrow.
        assertThat(Streak.count("2026-09-15", "2026-09-14")).isEqualTo(0)
    }

    @Test
    fun `a break restarts the streak the next day`() {
        val start = Streak.startAfterBreak("2026-09-14")
        assertThat(start).isEqualTo("2026-09-15")
        assertThat(Streak.count(start, "2026-09-14")).isEqualTo(0)
        assertThat(Streak.count(start, "2026-09-15")).isEqualTo(1)
    }

    @Test
    fun `a bypass is for one subject on one day`() {
        val bypasses = setOf(Streak.bypassKey("limit:3", "2026-09-14"))
        assertThat(Streak.isBypassed(bypasses, "limit:3", "2026-09-14")).isTrue()
        assertThat(Streak.isBypassed(bypasses, "limit:3", "2026-09-15")).isFalse()
        assertThat(Streak.isBypassed(bypasses, "limit:4", "2026-09-14")).isFalse()
    }
}
