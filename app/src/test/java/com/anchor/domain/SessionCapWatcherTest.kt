package com.anchor.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionCapWatcherTest {

    private val minute = 60_000L
    private val now = 1_757_000_000_000L

    @Test
    fun `a session just started gets the full cap`() {
        assertThat(SessionCapMath.remainingMillis(10, now, now)).isEqualTo(10 * minute)
    }

    @Test
    fun `a session already running gets the remainder`() {
        assertThat(SessionCapMath.remainingMillis(10, now - 4 * minute, now)).isEqualTo(6 * minute)
    }

    @Test
    fun `a session already past the cap returns zero, not a negative`() {
        assertThat(SessionCapMath.remainingMillis(10, now - 15 * minute, now)).isEqualTo(0)
    }

    @Test
    fun `no current session means no timer to arm`() {
        assertThat(SessionCapMath.remainingMillis(10, null, now)).isNull()
    }
}
