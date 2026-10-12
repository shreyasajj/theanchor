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
 */
@Singleton
class AndroidPhoneLocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) : PhoneLocationSource {

    override suspend fun current(freshOnly: Boolean): PhoneFix? = try {
        if (!hasPermission()) null else read(freshOnly)?.toFix()
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
    private suspend fun read(freshOnly: Boolean): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val enabled = manager.getProviders(true)

        val cached = enabled.mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
        if (cacheUsable(cached?.time, clock.millis(), freshOnly)) return cached

        val provider = PROVIDER_PREFERENCE.firstOrNull { it in enabled } ?: return null
        val timeout = if (freshOnly) CAPTURE_TIMEOUT_MILLIS else FRESH_FIX_TIMEOUT_MILLIS
        return withTimeoutOrNull(timeout) {
            suspendCancellableCoroutine<Location?> { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { cont.resume(it) }
            }
        }
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

        fun cacheUsable(fixTimeMillis: Long?, nowMillis: Long, freshOnly: Boolean): Boolean =
            !freshOnly && fixTimeMillis != null && nowMillis - fixTimeMillis <= MAX_CACHED_AGE_MILLIS

        /** Fused is quickest indoors; GPS last because it may never fix inside a house. */
        private val PROVIDER_PREFERENCE = listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        )
    }
}
