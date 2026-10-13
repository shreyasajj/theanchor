package com.anchor.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OpenBudgetTest {

    @Test
    fun `a new open needs a whole one left`() {
        assertThat(OpenBudget.isSpent(usedUnits = 1.0, dailyOpens = 2)).isFalse()
        assertThat(OpenBudget.isSpent(usedUnits = 1.5, dailyOpens = 2)).isTrue()
        assertThat(OpenBudget.isSpent(usedUnits = 2.0, dailyOpens = 2)).isTrue()
    }

    @Test
    fun `halves help once they add up to a whole`() {
        assertThat(OpenBudget.isSpent(usedUnits = 1.5, dailyOpens = 3)).isFalse()
        assertThat(OpenBudget.isSpent(usedUnits = 2.5, dailyOpens = 3)).isTrue()
    }

    @Test
    fun `a budget of zero is spent from the start`() {
        assertThat(OpenBudget.isSpent(usedUnits = 0.0, dailyOpens = 0)).isTrue()
    }
}
