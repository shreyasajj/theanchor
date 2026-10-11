package com.anchor.data.location

import com.anchor.data.ha.Presence
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PhonePresenceTest {

    private val home = SavedPlace(37.7749, -122.4194, 150)

    private fun fix(lat: Double, lon: Double = -122.4194, accuracy: Float? = 20f) =
        PhoneFix(lat, lon, accuracy, timeMillis = 0L)

    @Test
    fun `at the place is in scope`() {
        assertThat(PhonePresence.evaluate(fix(37.7749), home)).isEqualTo(Presence.IN_SCOPE)
    }

    @Test
    fun `just inside the radius is in scope`() {
        // 0.00134 degrees of latitude is about 149 m.
        assertThat(PhonePresence.evaluate(fix(37.7749 + 0.00134), home)).isEqualTo(Presence.IN_SCOPE)
    }

    @Test
    fun `just outside the radius is out of scope`() {
        // 0.001358 degrees of latitude is about 151 m.
        assertThat(PhonePresence.evaluate(fix(37.7749 + 0.001358), home)).isEqualTo(Presence.OUT_OF_SCOPE)
    }

    @Test
    fun `no fix is unknown`() {
        assertThat(PhonePresence.evaluate(null, home)).isEqualTo(Presence.UNKNOWN)
    }

    @Test
    fun `no place is unknown`() {
        assertThat(PhonePresence.evaluate(fix(37.7749), null)).isEqualTo(Presence.UNKNOWN)
    }

    @Test
    fun `a fix worse than 500 m is unknown, even at the place`() {
        assertThat(PhonePresence.evaluate(fix(37.7749, accuracy = 600f), home)).isEqualTo(Presence.UNKNOWN)
    }

    @Test
    fun `a fix with no reported accuracy is used`() {
        assertThat(PhonePresence.evaluate(fix(37.7749, accuracy = null), home)).isEqualTo(Presence.IN_SCOPE)
    }

    @Test
    fun `isUsable draws the line at 500 m`() {
        assertThat(PhonePresence.isUsable(fix(0.0, accuracy = 500f))).isTrue()
        assertThat(PhonePresence.isUsable(fix(0.0, accuracy = 500.1f))).isFalse()
    }
}
