package com.anchor.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class AnchorDateTest {

    private val zone = ZoneId.of("America/Los_Angeles")

    private fun anchorDateAt(iso: String) = AnchorDate(Clock.fixed(Instant.parse(iso), zone))

    @Test
    fun `today formats as ISO yyyy-MM-dd in the local zone`() {
        // 2026-09-09T15:30Z is 08:30 local on the same day.
        assertThat(anchorDateAt("2026-09-09T15:30:00Z").today()).isEqualTo("2026-09-09")
    }

    @Test
    fun `today rolls over using the local zone, not UTC`() {
        // 2026-09-10T05:00Z is 22:00 local on 2026-09-09.
        assertThat(anchorDateAt("2026-09-10T05:00:00Z").today()).isEqualTo("2026-09-09")
    }

    @Test
    fun `minuteOfDay reflects local wall-clock time`() {
        assertThat(anchorDateAt("2026-09-09T15:30:00Z").minuteOfDay()).isEqualTo(8 * 60 + 30)
    }

    @Test
    fun `nowMillis comes from the clock`() {
        assertThat(anchorDateAt("2026-09-09T15:30:00Z").nowMillis())
            .isEqualTo(Instant.parse("2026-09-09T15:30:00Z").toEpochMilli())
    }

    @Test
    fun `evening at 10pm anchors to the same calendar day`() {
        val date = anchorDateAt("2026-09-10T05:00:00Z")
        assertThat(date.eveningAnchorDate(eveningEndMinute = 5 * 60)).isEqualTo("2026-09-09")
    }

    @Test
    fun `evening at 1am anchors to the previous calendar day`() {
        val date = anchorDateAt("2026-09-10T08:00:00Z")
        assertThat(date.eveningAnchorDate(eveningEndMinute = 5 * 60)).isEqualTo("2026-09-09")
    }

    @Test
    fun `evening at 6am anchors to the current day, past the window end`() {
        val date = anchorDateAt("2026-09-10T13:00:00Z")
        assertThat(date.eveningAnchorDate(eveningEndMinute = 5 * 60)).isEqualTo("2026-09-10")
    }

    @Test
    fun `anchoring works across a month boundary`() {
        val date = anchorDateAt("2026-10-01T08:00:00Z")
        assertThat(date.eveningAnchorDate(eveningEndMinute = 5 * 60)).isEqualTo("2026-09-30")
    }
}
