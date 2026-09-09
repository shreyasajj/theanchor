package com.anchor.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class MorningAlarmSchedulerTest {

    private val zone = ZoneId.of("America/Los_Angeles")

    private fun millisAt(local: String): Long = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    private fun nextAt(nowLocal: String, startMinute: Int = 5 * 60): LocalDateTime =
        LocalDateTime.ofInstant(
            Instant.ofEpochMilli(NextMorningAlarm.nextTriggerMillis(millisAt(nowLocal), zone, startMinute)),
            zone,
        )

    @Test
    fun `before the window start, the alarm is today at the start`() {
        assertThat(nextAt("2026-09-09T03:00:00")).isEqualTo(LocalDateTime.parse("2026-09-09T05:00:00"))
    }

    @Test
    fun `after the window start, the alarm is tomorrow at the start`() {
        assertThat(nextAt("2026-09-09T09:00:00")).isEqualTo(LocalDateTime.parse("2026-09-10T05:00:00"))
    }

    @Test
    fun `exactly at the window start, the alarm moves to tomorrow`() {
        assertThat(nextAt("2026-09-09T05:00:00")).isEqualTo(LocalDateTime.parse("2026-09-10T05:00:00"))
    }

    @Test
    fun `late at night, the alarm is the next morning`() {
        assertThat(nextAt("2026-09-09T23:30:00")).isEqualTo(LocalDateTime.parse("2026-09-10T05:00:00"))
    }

    @Test
    fun `honours a custom window start`() {
        assertThat(nextAt("2026-09-09T03:00:00", startMinute = 6 * 60 + 30))
            .isEqualTo(LocalDateTime.parse("2026-09-09T06:30:00"))
    }

    @Test
    fun `rolls over a month boundary`() {
        assertThat(nextAt("2026-09-30T09:00:00")).isEqualTo(LocalDateTime.parse("2026-10-01T05:00:00"))
    }

    @Test
    fun `always returns a future instant`() {
        listOf("2026-09-09T00:00:00", "2026-09-09T05:00:00", "2026-09-09T23:59:59").forEach { now ->
            val next = NextMorningAlarm.nextTriggerMillis(millisAt(now), zone, 5 * 60)
            assertThat(next).isGreaterThan(millisAt(now))
        }
    }
}
