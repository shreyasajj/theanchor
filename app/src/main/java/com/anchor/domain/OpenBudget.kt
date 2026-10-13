package com.anchor.domain

/**
 * When a daily open budget is spent. An open ended early with the lock
 * button costs half, but a new open always needs a whole one left: halves
 * only help once two of them add up. So the day never goes over budget, and
 * "0.5 left" never invites an open that would cost 1.
 */
object OpenBudget {
    fun isSpent(usedUnits: Double, dailyOpens: Int): Boolean = dailyOpens - usedUnits < 1.0
}
