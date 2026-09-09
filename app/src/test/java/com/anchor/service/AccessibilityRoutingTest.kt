package com.anchor.service

import com.anchor.domain.EveningDecision
import com.anchor.domain.LimitDecision
import com.anchor.domain.LimitReason
import com.anchor.domain.SkipReason
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AccessibilityRoutingTest {

    private val allowEvening = EveningDecision.Allow(SkipReason.OUTSIDE_WINDOW)

    // --- LimitRouting ---

    @Test
    fun `both allowing means nothing is shown`() {
        assertThat(LimitRouting.route(LimitDecision.Allow, allowEvening)).isEqualTo(Route.None)
    }

    @Test
    fun `every Allow reason routes nowhere`() {
        SkipReason.entries.forEach { reason ->
            assertThat(LimitRouting.route(LimitDecision.Allow, EveningDecision.Allow(reason))).isEqualTo(Route.None)
        }
    }

    @Test
    fun `a limit block short-circuits the evening gate entirely`() {
        val blocked = LimitDecision.Blocked(LimitReason.DAILY_TIME, 123L)
        assertThat(LimitRouting.route(blocked, EveningDecision.Strict))
            .isEqualTo(Route.Blocked(LimitReason.DAILY_TIME, 123L))
    }

    @Test
    fun `the strict overlay is shown when no limit blocks`() {
        assertThat(LimitRouting.route(LimitDecision.Allow, EveningDecision.Strict)).isEqualTo(Route.StrictEvening)
    }

    @Test
    fun `the evening simple delay becomes a five second pause`() {
        assertThat(LimitRouting.route(LimitDecision.Allow, EveningDecision.SimpleDelay)).isEqualTo(Route.Pause(5))
    }

    @Test
    fun `a pre-open pause is shown when the evening gate is quiet`() {
        assertThat(LimitRouting.route(LimitDecision.Pause(30), allowEvening)).isEqualTo(Route.Pause(30))
    }

    @Test
    fun `a pause and a simple delay collapse into one longer pause`() {
        assertThat(LimitRouting.route(LimitDecision.Pause(30), EveningDecision.SimpleDelay)).isEqualTo(Route.Pause(30))
    }

    @Test
    fun `a strict overlay wins over a pre-open pause`() {
        assertThat(LimitRouting.route(LimitDecision.Pause(30), EveningDecision.Strict)).isEqualTo(Route.StrictEvening)
    }

    // --- PackageDebounce ---

    @Test
    fun `debounce suppresses a repeat of the same package inside the window`() {
        val debounce = PackageDebounce(windowMillis = 3_000)

        assertThat(debounce.shouldHandle("com.youtube", nowMillis = 1_000)).isTrue()
        assertThat(debounce.shouldHandle("com.youtube", nowMillis = 2_000)).isFalse()
        assertThat(debounce.shouldHandle("com.youtube", nowMillis = 5_000)).isTrue()
    }

    @Test
    fun `debounce does not suppress a different package`() {
        val debounce = PackageDebounce(windowMillis = 3_000)

        assertThat(debounce.shouldHandle("com.youtube", nowMillis = 1_000)).isTrue()
        assertThat(debounce.shouldHandle("com.instagram", nowMillis = 1_100)).isTrue()
    }

    @Test
    fun `clearing the debounce lets the same package through again`() {
        val debounce = PackageDebounce(windowMillis = 3_000)

        debounce.shouldHandle("com.youtube", nowMillis = 1_000)
        debounce.clear()
        assertThat(debounce.shouldHandle("com.youtube", nowMillis = 1_100)).isTrue()
    }
}
