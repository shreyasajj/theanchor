package com.anchor.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BreathingGuideTest {

    private val pattern = BreathPattern(inhaleSeconds = 4, holdSeconds = 2, exhaleSeconds = 6)

    private fun at(seconds: Double) = BreathingGuide.stateAt((seconds * 1000).toLong(), pattern)

    @Test
    fun `the cycle is the three phases together`() {
        assertThat(pattern.cycleSeconds).isEqualTo(12)
    }

    @Test
    fun `it starts on the in-breath`() {
        val s = at(0.0)
        assertThat(s.phase).isEqualTo(BreathPhase.INHALE)
        assertThat(s.secondsLeftInPhase).isEqualTo(4)
        assertThat(s.openness).isEqualTo(0f)
    }

    @Test
    fun `the in-breath fills up`() {
        assertThat(at(2.0).openness).isEqualTo(0.5f)
        assertThat(at(3.9).phase).isEqualTo(BreathPhase.INHALE)
    }

    @Test
    fun `the hold stays full`() {
        val s = at(5.0)
        assertThat(s.phase).isEqualTo(BreathPhase.HOLD)
        assertThat(s.openness).isEqualTo(1f)
        assertThat(s.secondsLeftInPhase).isEqualTo(1)
    }

    @Test
    fun `the out-breath empties`() {
        assertThat(at(6.0).phase).isEqualTo(BreathPhase.EXHALE)
        assertThat(at(6.0).openness).isEqualTo(1f)
        assertThat(at(9.0).openness).isEqualTo(0.5f)
        assertThat(at(11.9).openness).isWithin(0.02f).of(0f)
    }

    @Test
    fun `the next cycle starts over`() {
        val s = at(12.0)
        assertThat(s.phase).isEqualTo(BreathPhase.INHALE)
        assertThat(s.cycle).isEqualTo(1)
        assertThat(s.openness).isEqualTo(0f)
    }

    @Test
    fun `the phase countdown never reads zero`() {
        // Eyes are closed; "0" would be a beat of silence with no instruction.
        (0..119).forEach { tenth ->
            assertThat(at(tenth / 10.0).secondsLeftInPhase).isAtLeast(1)
        }
    }

    @Test
    fun `openness stays within bounds across two cycles`() {
        (0..240).forEach { tenth ->
            val openness = at(tenth / 10.0).openness
            assertThat(openness).isAtLeast(0f)
            assertThat(openness).isAtMost(1f)
        }
    }

    @Test
    fun `a negative elapsed time is treated as the start`() {
        assertThat(BreathingGuide.stateAt(-500, pattern).phase).isEqualTo(BreathPhase.INHALE)
    }

    @Test
    fun `every phase has an instruction`() {
        BreathPhase.entries.forEach { assertThat(BreathingGuide.label(it)).isNotEmpty() }
    }

    // --- The overall sit ---

    @Test
    fun `the sit counts down and floors at zero`() {
        assertThat(BreathingGuide.remainingSeconds(0, 1)).isEqualTo(60)
        assertThat(BreathingGuide.remainingSeconds(30_000, 1)).isEqualTo(30)
        assertThat(BreathingGuide.remainingSeconds(60_000, 1)).isEqualTo(0)
        assertThat(BreathingGuide.remainingSeconds(90_000, 1)).isEqualTo(0)
    }

    @Test
    fun `the clock reads as minutes and seconds`() {
        assertThat(BreathingGuide.formatClock(0)).isEqualTo("0:00")
        assertThat(BreathingGuide.formatClock(9)).isEqualTo("0:09")
        assertThat(BreathingGuide.formatClock(60)).isEqualTo("1:00")
        assertThat(BreathingGuide.formatClock(185)).isEqualTo("3:05")
    }

    @Test
    fun `the offered lengths start at one minute`() {
        assertThat(BreathingGuide.DURATION_CHOICES_MINUTES.first()).isEqualTo(1)
        assertThat(BreathingGuide.DURATION_CHOICES_MINUTES).isInOrder()
    }
}
