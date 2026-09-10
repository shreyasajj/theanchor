package com.anchor.domain

/**
 * A breathing pattern, in seconds per phase. The default is a gentle
 * wind-down: a longer exhale than inhale settles the nervous system, and a
 * short hold is easy for someone who has never done this before.
 */
data class BreathPattern(
    val inhaleSeconds: Int = 4,
    val holdSeconds: Int = 2,
    val exhaleSeconds: Int = 6,
) {
    val cycleSeconds: Int get() = inhaleSeconds + holdSeconds + exhaleSeconds
    val cycleMillis: Long get() = cycleSeconds * 1000L
}

enum class BreathPhase { INHALE, HOLD, EXHALE }

/**
 * @param phase what to do right now
 * @param secondsLeftInPhase counted down, so 4 then 3 then 2 then 1
 * @param openness 0 at an empty chest, 1 at a full one. Drives the circle.
 * @param cycle which breath this is, from 0
 */
data class BreathState(
    val phase: BreathPhase,
    val secondsLeftInPhase: Int,
    val openness: Float,
    val cycle: Int,
)

/** Pure breathing arithmetic, so the pacing is unit-testable. */
object BreathingGuide {

    val DURATION_CHOICES_MINUTES = listOf(1, 3, 5, 10)

    fun stateAt(elapsedMillis: Long, pattern: BreathPattern = BreathPattern()): BreathState {
        val elapsed = elapsedMillis.coerceAtLeast(0)
        val cycle = (elapsed / pattern.cycleMillis).toInt()
        val inCycle = elapsed % pattern.cycleMillis

        val inhaleEnd = pattern.inhaleSeconds * 1000L
        val holdEnd = inhaleEnd + pattern.holdSeconds * 1000L

        return when {
            inCycle < inhaleEnd -> BreathState(
                phase = BreathPhase.INHALE,
                secondsLeftInPhase = secondsLeft(inhaleEnd - inCycle),
                openness = if (inhaleEnd == 0L) 1f else (inCycle.toFloat() / inhaleEnd),
                cycle = cycle,
            )

            inCycle < holdEnd -> BreathState(
                phase = BreathPhase.HOLD,
                secondsLeftInPhase = secondsLeft(holdEnd - inCycle),
                openness = 1f,
                cycle = cycle,
            )

            else -> {
                val intoExhale = inCycle - holdEnd
                val exhaleMillis = pattern.exhaleSeconds * 1000L
                BreathState(
                    phase = BreathPhase.EXHALE,
                    secondsLeftInPhase = secondsLeft(pattern.cycleMillis - inCycle),
                    openness = if (exhaleMillis == 0L) 0f else 1f - (intoExhale.toFloat() / exhaleMillis),
                    cycle = cycle,
                )
            }
        }
    }

    /** Whole seconds still to go, rounded up, so the label never reads zero. */
    private fun secondsLeft(remainingMillis: Long): Int =
        maxOf(1, ((remainingMillis + 999) / 1000).toInt())

    fun label(phase: BreathPhase): String = when (phase) {
        BreathPhase.INHALE -> "Breathe in"
        BreathPhase.HOLD -> "Hold"
        BreathPhase.EXHALE -> "Breathe out"
    }

    /** Seconds still to sit, floored at zero. */
    fun remainingSeconds(elapsedMillis: Long, totalMinutes: Int): Int {
        val remaining = totalMinutes * 60_000L - elapsedMillis
        if (remaining <= 0) return 0
        return ((remaining + 999) / 1000).toInt()
    }

    fun formatClock(totalSeconds: Int): String =
        "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
