package com.anchor

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class ScaffoldTest {
    @Test
    fun `fixed clock is injectable and deterministic`() {
        val clock = Clock.fixed(Instant.parse("2026-09-09T07:30:00Z"), ZoneId.of("UTC"))
        assertThat(clock.instant().toString()).isEqualTo("2026-09-09T07:30:00Z")
    }
}
