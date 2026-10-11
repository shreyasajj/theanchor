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
    /** Never throws; null when there is no permission, no provider, or no fix in time. */
    suspend fun current(): PhoneFix?
}
