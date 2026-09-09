package com.anchor.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimeWindowTest {

    private fun at(hour: Int, minute: Int = 0) = hour * 60 + minute

    // --- Normal (non-wrapping) window: the morning, 05:00–12:00 ---

    @Test
    fun `includes the start minute`() {
        assertThat(TimeWindow.contains(at(5), at(5), at(12))).isTrue()
    }

    @Test
    fun `excludes the end minute`() {
        assertThat(TimeWindow.contains(at(12), at(5), at(12))).isFalse()
    }

    @Test
    fun `includes a minute in the middle`() {
        assertThat(TimeWindow.contains(at(7, 30), at(5), at(12))).isTrue()
    }

    @Test
    fun `excludes a minute before the start`() {
        assertThat(TimeWindow.contains(at(4, 59), at(5), at(12))).isFalse()
    }

    @Test
    fun `excludes a minute after the end`() {
        assertThat(TimeWindow.contains(at(13), at(5), at(12))).isFalse()
    }

    // --- Wrapping window: the evening, 20:00–05:00 ---

    @Test
    fun `wrapping window includes late evening`() {
        assertThat(TimeWindow.contains(at(22), at(20), at(5))).isTrue()
    }

    @Test
    fun `wrapping window includes just after midnight`() {
        assertThat(TimeWindow.contains(at(0, 30), at(20), at(5))).isTrue()
    }

    @Test
    fun `wrapping window includes the start minute`() {
        assertThat(TimeWindow.contains(at(20), at(20), at(5))).isTrue()
    }

    @Test
    fun `wrapping window excludes the end minute`() {
        assertThat(TimeWindow.contains(at(5), at(20), at(5))).isFalse()
    }

    @Test
    fun `wrapping window excludes the afternoon`() {
        assertThat(TimeWindow.contains(at(15), at(20), at(5))).isFalse()
    }

    @Test
    fun `wrapping window excludes 19_59`() {
        assertThat(TimeWindow.contains(at(19, 59), at(20), at(5))).isFalse()
    }

    // --- Degenerate windows ---

    @Test
    fun `a zero-length window contains nothing`() {
        assertThat(TimeWindow.contains(at(9), at(9), at(9))).isFalse()
        assertThat(TimeWindow.contains(at(0), at(9), at(9))).isFalse()
    }

    @Test
    fun `wraps reports whether the window crosses midnight`() {
        assertThat(TimeWindow.wraps(at(20), at(5))).isTrue()
        assertThat(TimeWindow.wraps(at(5), at(12))).isFalse()
        assertThat(TimeWindow.wraps(at(9), at(9))).isFalse()
    }
}
