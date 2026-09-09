package com.anchor.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class AnchorDateUsageTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private val reset = 4 * 60   // 04:00

    private fun at(local: String) = AnchorDate(
        Clock.fixed(LocalDateTime.parse(local).atZone(zone).toInstant(), zone)
    )

    private fun localOf(millis: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)

    @Test
    fun `mid-afternoon belongs to the current calendar day`() {
        assertThat(at("2026-09-09T15:00:00").usageDay(reset)).isEqualTo("2026-09-09")
    }

    @Test
    fun `just after the reset belongs to the current day`() {
        assertThat(at("2026-09-09T04:00:00").usageDay(reset)).isEqualTo("2026-09-09")
    }

    @Test
    fun `just before the reset still belongs to the previous day`() {
        assertThat(at("2026-09-09T03:59:00").usageDay(reset)).isEqualTo("2026-09-08")
    }

    @Test
    fun `1am belongs to the previous day, so a late night shares its budget`() {
        assertThat(at("2026-09-09T01:00:00").usageDay(reset)).isEqualTo("2026-09-08")
    }

    @Test
    fun `midnight reset makes the usage day the calendar day`() {
        assertThat(at("2026-09-09T01:00:00").usageDay(dayResetMinute = 0)).isEqualTo("2026-09-09")
    }

    @Test
    fun `day start is the reset time of the usage day`() {
        val start = at("2026-09-09T15:00:00").usageDayStartMillis(reset)
        assertThat(localOf(start)).isEqualTo(LocalDateTime.parse("2026-09-09T04:00:00"))
    }

    @Test
    fun `day start before the reset points at yesterday's reset`() {
        val start = at("2026-09-09T01:00:00").usageDayStartMillis(reset)
        assertThat(localOf(start)).isEqualTo(LocalDateTime.parse("2026-09-08T04:00:00"))
    }

    @Test
    fun `next reset is tomorrow's reset time from the afternoon`() {
        val next = at("2026-09-09T15:00:00").nextUsageResetMillis(reset)
        assertThat(localOf(next)).isEqualTo(LocalDateTime.parse("2026-09-10T04:00:00"))
    }

    @Test
    fun `next reset is later today when it is before the reset`() {
        val next = at("2026-09-09T01:00:00").nextUsageResetMillis(reset)
        assertThat(localOf(next)).isEqualTo(LocalDateTime.parse("2026-09-09T04:00:00"))
    }

    @Test
    fun `next reset is always strictly in the future`() {
        listOf("2026-09-09T03:59:59", "2026-09-09T04:00:00", "2026-09-09T23:59:59").forEach { now ->
            val date = at(now)
            assertThat(date.nextUsageResetMillis(reset)).isGreaterThan(date.nowMillis())
        }
    }

    @Test
    fun `the window from day start to next reset is exactly 24 hours`() {
        val date = at("2026-09-09T15:00:00")
        val span = date.nextUsageResetMillis(reset) - date.usageDayStartMillis(reset)
        assertThat(span).isEqualTo(24L * 60 * 60 * 1000)
    }

    @Test
    fun `day start rolls back across a month boundary`() {
        val start = at("2026-10-01T02:00:00").usageDayStartMillis(reset)
        assertThat(localOf(start)).isEqualTo(LocalDateTime.parse("2026-09-30T04:00:00"))
    }
}
