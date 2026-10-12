package com.anchor.data.location

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FreshFixPolicyTest {

    private val now = 1_000_000_000L
    private val seconds = 1_000L

    @Test
    fun `a fresh fix is asked for when nothing has failed`() {
        assertThat(AndroidPhoneLocationSource.freshAllowed(null, now, freshOnly = false)).isTrue()
    }

    @Test
    fun `a failure in the last 45 s skips the wait for a check`() {
        assertThat(AndroidPhoneLocationSource.freshAllowed(now - 30 * seconds, now, freshOnly = false)).isFalse()
    }

    @Test
    fun `after 45 s a check tries again`() {
        assertThat(AndroidPhoneLocationSource.freshAllowed(now - 45 * seconds, now, freshOnly = false)).isTrue()
    }

    @Test
    fun `saving a place always tries, whatever failed before`() {
        assertThat(AndroidPhoneLocationSource.freshAllowed(now - 1 * seconds, now, freshOnly = true)).isTrue()
    }
}
