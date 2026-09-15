package com.anchor.ui.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UsageRowTest {

    private fun row(used: Int, limit: Int?, opens: Int = 0, limitOpens: Int? = null) =
        UsageRow("X", usedMinutes = used, limitMinutes = limit, opens = opens, limitOpens = limitOpens)

    @Test
    fun `fraction is null when there is no budget`() {
        assertThat(row(used = 20, limit = null).fraction).isNull()
    }

    @Test
    fun `fraction follows the open budget when that is the mode`() {
        assertThat(row(used = 0, limit = null, opens = 2, limitOpens = 4).fraction).isEqualTo(0.5f)
    }

    @Test
    fun `fraction reflects progress through the budget`() {
        assertThat(row(used = 15, limit = 30).fraction).isEqualTo(0.5f)
    }

    @Test
    fun `fraction is clamped at one when over budget`() {
        assertThat(row(used = 45, limit = 30).fraction).isEqualTo(1f)
    }

    @Test
    fun `a zero-minute limit does not divide by zero`() {
        assertThat(row(used = 5, limit = 0).fraction).isEqualTo(1f)
    }

    @Test
    fun `exhausted when the time budget is reached`() {
        assertThat(row(used = 30, limit = 30).isExhausted).isTrue()
        assertThat(row(used = 29, limit = 30).isExhausted).isFalse()
    }

    @Test
    fun `exhausted when the open count is used up`() {
        assertThat(row(used = 0, limit = null, opens = 5, limitOpens = 5).isExhausted).isTrue()
    }

    @Test
    fun `not exhausted when neither limit is set`() {
        assertThat(row(used = 500, limit = null).isExhausted).isFalse()
    }
}

class LimitRowTextTest {

    private fun row(status: com.anchor.domain.LimitStatus) =
        UsageRow("X", usedMinutes = 0, limitMinutes = null, opens = 0, limitOpens = 2, status = status)

    @Test
    fun `countdown reads as minutes and seconds and never goes negative`() {
        assertThat(countdown(272_000)).isEqualTo("4:32")
        assertThat(countdown(59_000)).isEqualTo("0:59")
        assertThat(countdown(-5_000)).isEqualTo("0:00")
    }

    @Test
    fun `a running session counts down in the detail`() {
        val now = 1_000_000L
        val text = statusDetail(row(com.anchor.domain.LimitStatus.InSession(now + 90_000, inForeground = true)), now)
        assertThat(text).isEqualTo("1:30 left in this session.")
    }

    @Test
    fun `the pill names the state`() {
        assertThat(statusPill(row(com.anchor.domain.LimitStatus.Available)).first).isEqualTo("Available")
        assertThat(statusPill(row(com.anchor.domain.LimitStatus.Spent(0))).first).isEqualTo("Spent")
        assertThat(statusPill(row(com.anchor.domain.LimitStatus.Cooldown(0))).first).isEqualTo("Cooling down")
        assertThat(statusPill(row(com.anchor.domain.LimitStatus.InSession(1, inForeground = false))).first).isEqualTo("In session")
        assertThat(statusPill(row(com.anchor.domain.LimitStatus.InSession(1, inForeground = true))).first).isEqualTo("In use")
    }
}
