package com.anchor.data.usage

import android.app.usage.UsageEvents
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UsageEventMappingTest {

    @Test
    fun `ACTIVITY_RESUMED maps to FOREGROUND`() {
        assertThat(UsageEventMapping.map(UsageEvents.Event.ACTIVITY_RESUMED)).isEqualTo(UsageEvent.Type.FOREGROUND)
    }

    @Test
    fun `ACTIVITY_PAUSED maps to BACKGROUND`() {
        assertThat(UsageEventMapping.map(UsageEvents.Event.ACTIVITY_PAUSED)).isEqualTo(UsageEvent.Type.BACKGROUND)
    }

    @Test
    fun `ACTIVITY_STOPPED maps to BACKGROUND`() {
        assertThat(UsageEventMapping.map(UsageEvents.Event.ACTIVITY_STOPPED)).isEqualTo(UsageEvent.Type.BACKGROUND)
    }

    @Test
    fun `unrelated event types are dropped`() {
        assertThat(UsageEventMapping.map(UsageEvents.Event.CONFIGURATION_CHANGE)).isNull()
        assertThat(UsageEventMapping.map(UsageEvents.Event.USER_INTERACTION)).isNull()
    }

    @Test
    fun `the query reaches back before the day start so cooldowns survive the reset`() {
        val dayStart = 1_757_000_000_000L
        val from = UsageEventMapping.queryFrom(dayStart)

        assertThat(from).isLessThan(dayStart)
        assertThat(dayStart - from).isEqualTo(UsageEventMapping.COOLDOWN_LOOKBACK_MILLIS)
    }
}
