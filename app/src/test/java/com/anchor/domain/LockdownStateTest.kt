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
        assertThat(LockdownState.kind).isNull()
    }

    @Test
    fun `begin activates and end deactivates`() {
        LockdownState.begin(LockKind.MORNING)
        assertThat(LockdownState.active).isTrue()
        LockdownState.end()
        assertThat(LockdownState.active).isFalse()
    }

    @Test
    fun `begin is idempotent`() {
        LockdownState.begin(LockKind.MORNING)
        LockdownState.begin(LockKind.MORNING)
        LockdownState.end()
        assertThat(LockdownState.active).isFalse()
    }

    @Test
    fun `the flow reflects which lock is up`() {
        LockdownState.begin(LockKind.EVENING_SIT)
        assertThat(LockdownState.kindFlow.value).isEqualTo(LockKind.EVENING_SIT)
        LockdownState.end()
        assertThat(LockdownState.kindFlow.value).isNull()
    }
}
