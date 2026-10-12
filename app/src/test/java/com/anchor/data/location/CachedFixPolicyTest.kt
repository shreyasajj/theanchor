package com.anchor.data.location

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CachedFixPolicyTest {

    private val now = 1_000_000_000L
    private val minutes = 60_000L

    @Test
    fun `a cached fix up to ten minutes old answers a background check`() {
        assertThat(AndroidPhoneLocationSource.cacheUsable(now - 10 * minutes, now, freshOnly = false)).isTrue()
    }

    @Test
    fun `an older cached fix does not`() {
        assertThat(AndroidPhoneLocationSource.cacheUsable(now - 11 * minutes, now, freshOnly = false)).isFalse()
    }

    @Test
    fun `saving a place never uses the cache, however recent`() {
        assertThat(AndroidPhoneLocationSource.cacheUsable(now - 1_000, now, freshOnly = true)).isFalse()
    }

    @Test
    fun `no cached fix is never usable`() {
        assertThat(AndroidPhoneLocationSource.cacheUsable(null, now, freshOnly = false)).isFalse()
    }
}
