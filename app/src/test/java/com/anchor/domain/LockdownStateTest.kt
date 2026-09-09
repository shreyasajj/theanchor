package com.anchor.domain

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

class LockdownStateTest {

    @After
    fun tearDown() = LockdownState.end()

    @Test
    fun `starts inactive`() {
        assertThat(LockdownState.active).isFalse()
    }

    @Test
    fun `begin activates and end deactivates`() {
        LockdownState.begin()
        assertThat(LockdownState.active).isTrue()
        LockdownState.end()
        assertThat(LockdownState.active).isFalse()
    }

    @Test
    fun `begin is idempotent`() {
        LockdownState.begin()
        LockdownState.begin()
        LockdownState.end()
        assertThat(LockdownState.active).isFalse()
    }

    @Test
    fun `the flow reflects the current value`() {
        LockdownState.begin()
        assertThat(LockdownState.activeFlow.value).isTrue()
        LockdownState.end()
        assertThat(LockdownState.activeFlow.value).isFalse()
    }
}
