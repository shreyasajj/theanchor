package com.anchor.data.location

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    /** Mean Earth radius (IUGG). */
    private const val EARTH_RADIUS_METERS = 6_371_008.8

    /**
     * Great-circle distance by the haversine formula. Plain Kotlin rather
     * than Location.distanceBetween so it runs in a JVM unit test; at house
     * scale the spherical error is far below GPS noise.
     */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
