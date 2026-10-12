package com.anchor.data.location

/**
 * A point on the map and how far from it still counts as "there". Persisted
 * as one "lat,lon,radius" string so a place is written and read atomically.
 */
data class SavedPlace(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int = DEFAULT_RADIUS_METERS,
) {
    fun format(): String = "$latitude,$longitude,$radiusMeters"

    companion object {
        const val DEFAULT_RADIUS_METERS = 150
        const val MIN_RADIUS_METERS = 25
        const val MAX_RADIUS_METERS = 5_000

        fun clampRadius(meters: Int): Int = meters.coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS)

        /** Null for anything malformed or off the globe; never throws. */
        fun parse(raw: String?): SavedPlace? {
            val parts = raw?.split(',')?.map { it.trim() } ?: return null
            if (parts.size != 3) return null
            val lat = parts[0].toDoubleOrNull() ?: return null
            val lon = parts[1].toDoubleOrNull() ?: return null
            val radius = parts[2].toIntOrNull() ?: return null
            // NaN fails both range checks, so it is rejected here too.
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return SavedPlace(lat, lon, clampRadius(radius))
        }
    }
}
