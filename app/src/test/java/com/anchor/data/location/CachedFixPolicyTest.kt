package com.anchor.data.location

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CachedFixPolicyTest {

    private val now = 1_000_000_000L
    private val minutes = 60_000L

    private fun fixAt(ageMillis: Long, accuracy: Float? = 20f) =
        PhoneFix(1.0, 2.0, accuracy, timeMillis = now - ageMillis)

    @Test
    fun `a cached fix up to ten minutes old answers a background check`() {
        val fix = fixAt(10 * minutes)
        assertThat(AndroidPhoneLocationSource.bestCached(listOf(fix), now, freshOnly = false)).isEqualTo(fix)
    }

    @Test
    fun `an older cached fix does not`() {
        assertThat(AndroidPhoneLocationSource.bestCached(listOf(fixAt(11 * minutes)), now, freshOnly = false)).isNull()
    }

    @Test
    fun `saving a place never uses the cache, however recent`() {
        assertThat(AndroidPhoneLocationSource.bestCached(listOf(fixAt(1_000)), now, freshOnly = true)).isNull()
    }

    @Test
    fun `no cached fix is never usable`() {
        assertThat(AndroidPhoneLocationSource.bestCached(emptyList(), now, freshOnly = false)).isNull()
    }

    @Test
    fun `the newest usable fix wins over a newer vague one`() {
        val precise = fixAt(3 * minutes, accuracy = 20f)
        val vague = fixAt(1 * minutes, accuracy = 1_200f)
        assertThat(AndroidPhoneLocationSource.bestCached(listOf(vague, precise), now, freshOnly = false))
            .isEqualTo(precise)
    }

    @Test
    fun `only vague cached fixes mean asking for a fresh one`() {
        assertThat(AndroidPhoneLocationSource.bestCached(listOf(fixAt(1 * minutes, 1_200f)), now, freshOnly = false))
            .isNull()
    }
}
