package com.anchor.service

import com.anchor.data.usage.LimitMode
import com.anchor.data.usage.AppLimit
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RelockBubbleTest {

    // --- When the button belongs on screen ---

    @Test
    fun `it shows only for an app with an active limit`() {
        assertThat(RelockButton.shouldShowFor(null)).isFalse()
        assertThat(RelockButton.shouldShowFor(AppLimit("com.x"))).isFalse()
        assertThat(RelockButton.shouldShowFor(AppLimit("com.x", enabled = false, limitMode = LimitMode.TIME, dailyMinutes = 10))).isFalse()
        assertThat(RelockButton.shouldShowFor(AppLimit("com.x", sessionMinutes = 10))).isTrue()
    }

    @Test
    fun `turning it off in settings hides it everywhere`() {
        val limited = AppLimit("com.x", sessionMinutes = 10)
        assertThat(RelockButton.shouldShowFor(limited, enabledInSettings = false)).isFalse()
        assertThat(RelockButton.shouldShowFor(limited, enabledInSettings = true)).isTrue()
    }

    // --- Tap against drag ---

    @Test
    fun `a still finger is a tap`() {
        assertThat(BubbleTouch.isClick(0f, 0f, 80)).isTrue()
    }

    @Test
    fun `a small wobble is still a tap`() {
        assertThat(BubbleTouch.isClick(6f, -5f, 120)).isTrue()
    }

    @Test
    fun `a real drag is not a tap`() {
        assertThat(BubbleTouch.isClick(120f, 0f, 300)).isFalse()
        assertThat(BubbleTouch.isClick(0f, -200f, 300)).isFalse()
    }

    @Test
    fun `movement is judged in both directions`() {
        assertThat(BubbleTouch.isClick(-120f, 0f, 100)).isFalse()
        assertThat(BubbleTouch.isClick(0f, 120f, 100)).isFalse()
    }

    @Test
    fun `a long rest is not a tap, so the app is not locked by accident`() {
        assertThat(BubbleTouch.isClick(0f, 0f, BubbleTouch.CLICK_MAX_MILLIS + 1)).isFalse()
    }

    @Test
    fun `exactly at the slop and the time limit still counts`() {
        assertThat(
            BubbleTouch.isClick(BubbleTouch.CLICK_SLOP_PX, BubbleTouch.CLICK_SLOP_PX, BubbleTouch.CLICK_MAX_MILLIS)
        ).isTrue()
    }
}
