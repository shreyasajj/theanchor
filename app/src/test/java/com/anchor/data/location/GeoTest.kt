package com.anchor.data.location

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GeoTest {

    @Test
    fun `the same point is zero metres away`() {
        assertThat(Geo.distanceMeters(37.7749, -122.4194, 37.7749, -122.4194)).isWithin(0.001).of(0.0)
    }

    @Test
    fun `one degree of latitude is about 111 km`() {
        assertThat(Geo.distanceMeters(0.0, 0.0, 1.0, 0.0)).isWithin(111_195 * 0.005).of(111_195.0)
    }

    @Test
    fun `house-scale distances are accurate`() {
        // 0.001 degrees of latitude is about 111 m.
        assertThat(Geo.distanceMeters(37.7749, -122.4194, 37.7759, -122.4194)).isWithin(1.0).of(111.2)
    }

    @Test
    fun `long distances are within half a percent`() {
        // Big Ben to the Statue of Liberty, about 5575 km.
        val d = Geo.distanceMeters(51.5007, -0.1246, 40.6892, -74.0445)
        assertThat(d).isWithin(5_574_840 * 0.005).of(5_574_840.0)
    }
}
