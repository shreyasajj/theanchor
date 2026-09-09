package com.anchor.ui.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UsageRowTest {

    private fun row(used: Int, limit: Int?, opens: Int = 0, limitOpens: Int? = null) =
        UsageRow("com.x", "X", usedMinutes = used, limitMinutes = limit, opens = opens, limitOpens = limitOpens)

    @Test
    fun `fraction is null when there is no time limit`() {
        assertThat(row(used = 20, limit = null).timeFraction).isNull()
    }

    @Test
    fun `fraction reflects progress through the budget`() {
        assertThat(row(used = 15, limit = 30).timeFraction).isEqualTo(0.5f)
    }

    @Test
    fun `fraction is clamped at one when over budget`() {
        assertThat(row(used = 45, limit = 30).timeFraction).isEqualTo(1f)
    }

    @Test
    fun `a zero-minute limit does not divide by zero`() {
        assertThat(row(used = 5, limit = 0).timeFraction).isEqualTo(1f)
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
