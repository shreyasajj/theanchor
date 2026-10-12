package com.anchor.data.location

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SavedPlaceTest {

    @Test
    fun `format and parse round-trip, including negative coordinates`() {
        val place = SavedPlace(-33.8688, -151.2093, 75)
        assertThat(SavedPlace.parse(place.format())).isEqualTo(place)
    }

    @Test
    fun `parse rejects malformed input`() {
        assertThat(SavedPlace.parse(null)).isNull()
        assertThat(SavedPlace.parse("")).isNull()
        assertThat(SavedPlace.parse("1.0,2.0")).isNull()
        assertThat(SavedPlace.parse("a,b,c")).isNull()
        assertThat(SavedPlace.parse("1.0,2.0,wide")).isNull()
        assertThat(SavedPlace.parse("NaN,2.0,150")).isNull()
    }

    @Test
    fun `parse rejects coordinates off the globe`() {
        assertThat(SavedPlace.parse("91.0,0.0,150")).isNull()
        assertThat(SavedPlace.parse("0.0,181.0,150")).isNull()
    }

    @Test
    fun `parse clamps an out-of-range radius`() {
        assertThat(SavedPlace.parse("1.0,2.0,0")?.radiusMeters).isEqualTo(25)
        assertThat(SavedPlace.parse("1.0,2.0,99999")?.radiusMeters).isEqualTo(5_000)
    }

    @Test
    fun `clampRadius keeps the radius between 25 and 5000 metres`() {
        assertThat(SavedPlace.clampRadius(10)).isEqualTo(25)
        assertThat(SavedPlace.clampRadius(150)).isEqualTo(150)
        assertThat(SavedPlace.clampRadius(10_000)).isEqualTo(5_000)
    }
}
