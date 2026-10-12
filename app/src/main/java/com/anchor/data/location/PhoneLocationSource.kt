package com.anchor.data.location

/** One reading from the phone. [accuracyMeters] is null when the provider gave none. */
data class PhoneFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val timeMillis: Long,
)

/** The seam that keeps LocationManager out of every unit test. */
interface PhoneLocationSource {
    /**
     * Never throws; null when there is no permission, no provider, or no fix
     * in time. [freshOnly] skips any cached fix: saving a place must record
     * where the phone is now, not where it was a few minutes ago.
     */
    suspend fun current(freshOnly: Boolean = false): PhoneFix?
}
