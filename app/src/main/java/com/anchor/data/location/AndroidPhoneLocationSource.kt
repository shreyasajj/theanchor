package com.anchor.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Platform LocationManager only, no Play Services, so it also works on a
 * de-Googled phone. A cached fix younger than [MAX_CACHED_AGE_MILLIS]
 * answers instantly; otherwise one fresh fix is requested and abandoned
 * after [FRESH_FIX_TIMEOUT_MILLIS], the same budget as a Home Assistant call.
 * Saving a place asks for a fresh fix only, and waits longer for it.
 * A fresh request that just failed is not repeated for
 * [FAILURE_MEMORY_MILLIS]: a phone that cannot fix indoors would otherwise
 * make every blocked-app open wait the full timeout again.
 */
@Singleton
class AndroidPhoneLocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) : PhoneLocationSource {

    /** When the last fresh request came back empty; null after a success. */
    @Volatile private var lastFreshFailureAt: Long? = null

    override suspend fun current(freshOnly: Boolean): PhoneFix? = try {
        if (!hasPermission()) null else read(freshOnly)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        // Same rule as the HA client: a location problem must never crash the
        // accessibility service, and every failure has the same safe answer.
        null
    }

    private fun hasPermission(): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    @SuppressLint("MissingPermission") // hasPermission() is checked before every read
    private suspend fun read(freshOnly: Boolean): PhoneFix? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val enabled = manager.getProviders(true)

        val cached = enabled.mapNotNull { manager.getLastKnownLocation(it)?.toFix() }
        bestCached(cached, clock.millis(), freshOnly)?.let { return it }

        if (!freshAllowed(lastFreshFailureAt, clock.millis(), freshOnly)) return null
        val provider = PROVIDER_PREFERENCE.firstOrNull { it in enabled } ?: return null
        val timeout = if (freshOnly) CAPTURE_TIMEOUT_MILLIS else FRESH_FIX_TIMEOUT_MILLIS
        val fresh = withTimeoutOrNull(timeout) {
            suspendCancellableCoroutine<Location?> { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { cont.resume(it) }
            }
        }?.toFix()
        lastFreshFailureAt = if (fresh == null) clock.millis() else null
        return fresh
    }

    private fun Location.toFix() = PhoneFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        timeMillis = time,
    )

    companion object {
        const val MAX_CACHED_AGE_MILLIS = 10L * 60 * 1000
        const val FRESH_FIX_TIMEOUT_MILLIS = 5_000L

        /** Someone is waiting on a button, not a blocked app; give GPS time to fix. */
        const val CAPTURE_TIMEOUT_MILLIS = 15_000L

        const val FAILURE_MEMORY_MILLIS = 45_000L

        /** Saving a place always tries; a check skips the wait right after a failure. */
        fun freshAllowed(lastFailureMillis: Long?, nowMillis: Long, freshOnly: Boolean): Boolean =
            freshOnly || lastFailureMillis == null || nowMillis - lastFailureMillis >= FAILURE_MEMORY_MILLIS

        /**
         * The newest cached fix that is recent and precise enough, or null to
         * ask for a fresh one. Precision is filtered first: a newer cell-tower
         * guess must not hide a slightly older Wi-Fi or GPS fix.
         */
        fun bestCached(cached: List<PhoneFix>, nowMillis: Long, freshOnly: Boolean): PhoneFix? =
            if (freshOnly) null
            else cached
                .filter { PhonePresence.isUsable(it) && nowMillis - it.timeMillis <= MAX_CACHED_AGE_MILLIS }
                .maxByOrNull { it.timeMillis }

        /** Fused is quickest indoors; GPS last because it may never fix inside a house. */
        private val PROVIDER_PREFERENCE = listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        )
    }
}
