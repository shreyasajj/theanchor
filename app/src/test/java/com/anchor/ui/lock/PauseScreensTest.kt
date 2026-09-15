package com.anchor.ui.lock

import com.anchor.domain.EveningDecision
import com.anchor.domain.LimitDecision
import com.anchor.domain.LimitReason
import com.anchor.domain.SkipReason
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SimpleDelayTimerTest {

    @Test
    fun `the spec's delay is five seconds`() {
        assertThat(SimpleDelayTimer.DEFAULT_SECONDS).isEqualTo(5)
    }

    @Test
    fun `counts down from the total`() {
        assertThat(SimpleDelayTimer.remaining(0, 5)).isEqualTo(5)
        assertThat(SimpleDelayTimer.remaining(1_000, 5)).isEqualTo(4)
        assertThat(SimpleDelayTimer.remaining(4_500, 5)).isEqualTo(1)
    }

    @Test
    fun `reaches zero and never goes negative`() {
        assertThat(SimpleDelayTimer.remaining(5_000, 5)).isEqualTo(0)
        assertThat(SimpleDelayTimer.remaining(60_000, 5)).isEqualTo(0)
    }

    @Test
    fun `a partial second still shows the higher number`() {
        assertThat(SimpleDelayTimer.remaining(400, 5)).isEqualTo(5)
    }
}

class PauseCoalescingTest {

    @Test
    fun `no pause when neither gate asks for one`() {
        assertThat(
            PauseCoalescing.secondsFor(EveningDecision.Allow(SkipReason.OUTSIDE_WINDOW), LimitDecision.Allow)
        ).isEqualTo(0)
    }

    @Test
    fun `the evening simple delay alone is five seconds`() {
        assertThat(PauseCoalescing.secondsFor(EveningDecision.SimpleDelay, LimitDecision.Allow)).isEqualTo(5)
    }

    @Test
    fun `a pre-open pause alone uses its configured length`() {
        assertThat(
            PauseCoalescing.secondsFor(EveningDecision.Allow(SkipReason.OUTSIDE_WINDOW), LimitDecision.Pause(30))
        ).isEqualTo(30)
    }

    @Test
    fun `both asking gives one screen at the longer duration, never two`() {
        assertThat(PauseCoalescing.secondsFor(EveningDecision.SimpleDelay, LimitDecision.Pause(30))).isEqualTo(30)
    }

    @Test
    fun `a pause shorter than the evening delay is raised to it`() {
        assertThat(PauseCoalescing.secondsFor(EveningDecision.SimpleDelay, LimitDecision.Pause(3))).isEqualTo(5)
    }

    @Test
    fun `the strict overlay is not a pause`() {
        assertThat(PauseCoalescing.secondsFor(EveningDecision.Strict, LimitDecision.Allow)).isEqualTo(0)
    }

    @Test
    fun `a strict overlay still honours a configured pre-open pause`() {
        assertThat(PauseCoalescing.secondsFor(EveningDecision.Strict, LimitDecision.Pause(30))).isEqualTo(30)
    }
}

class LimitCopyTest {

    private val now = 1_757_000_000_000L

    @Test
    fun `each block reason has its own title`() {
        val titles = LimitReason.entries.map { LimitCopy.title(it) }
        assertThat(titles).containsNoDuplicates()
        assertThat(titles.none { it.isBlank() }).isTrue()
    }

    @Test
    fun `a cooldown block says how long is left`() {
        assertThat(LimitCopy.body(LimitReason.COOLDOWN, now + 25 * 60_000L, now)).contains("25 minutes")
    }

    @Test
    fun `a sub-minute wait rounds up rather than saying zero`() {
        assertThat(LimitCopy.body(LimitReason.COOLDOWN, now + 30_000L, now)).contains("1 minute")
    }

    @Test
    fun `a daily block says when it resets rather than counting minutes`() {
        assertThat(LimitCopy.body(LimitReason.DAILY_TIME, now + 9 * 60 * 60_000L, now)).contains("resets")
    }

    @Test
    fun `a spent session with no cooldown says a new one may start`() {
        assertThat(LimitCopy.body(LimitReason.SESSION_CAP, now, now)).contains("new one")
        assertThat(LimitCopy.body(LimitReason.SESSION_CAP, now + 20 * 60_000L, now)).contains("20 minutes")
    }

    @Test
    fun `the streak door names what it costs`() {
        assertThat(LimitCopy.breakLabel(0)).isEqualTo("Open anyway")
        assertThat(LimitCopy.breakLabel(1)).contains("1-day streak")
        assertThat(LimitCopy.breakLabel(7)).contains("7-day streak")
    }
}
