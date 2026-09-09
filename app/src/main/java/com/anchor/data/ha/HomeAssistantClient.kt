package com.anchor.data.ha

import com.anchor.data.settings.AnchorSettings
import javax.inject.Inject
import javax.inject.Singleton

sealed interface HaResult {
    data class Ok(val state: HaStateDto) : HaResult

    /**
     * Home Assistant could not be reached or understood. Every caller MUST
     * treat this as "do less blocking" (the fail-open constraint).
     */
    data object Unavailable : HaResult
}

@Singleton
open class HomeAssistantClient @Inject constructor(
    private val api: HomeAssistantApi,
) {
    /**
     * Never throws. Any failure (no config, DNS, timeout, 4xx, 5xx, bad JSON)
     * collapses to [HaResult.Unavailable].
     */
    open suspend fun fetchState(baseUrl: String, token: String, entityId: String): HaResult {
        if (baseUrl.isBlank() || token.isBlank() || entityId.isBlank()) {
            return HaResult.Unavailable
        }
        val url = "${baseUrl.trimEnd('/')}/api/states/$entityId"
        return try {
            HaResult.Ok(api.state(url, "Bearer $token"))
        } catch (t: Throwable) {
            // The one sanctioned blanket catch: a blocking decision must never
            // crash the accessibility service, and every failure has the same
            // safe answer.
            HaResult.Unavailable
        }
    }

    suspend fun fetchDeviceTracker(settings: AnchorSettings): HaResult =
        fetchState(
            baseUrl = settings.haBaseUrl,
            token = settings.haToken,
            entityId = settings.haDeviceTrackerEntityId,
        )
}
