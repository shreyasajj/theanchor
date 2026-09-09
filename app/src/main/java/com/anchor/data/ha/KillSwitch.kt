package com.anchor.data.ha

import com.anchor.data.settings.AnchorSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ACTIVE   -> the user has flipped the HA input_boolean; skip all blocking.
 * INACTIVE -> block normally.
 * UNKNOWN  -> HA is unreachable. By default this does NOT disable blocking:
 *             turning off Wi-Fi must not be an escape hatch. The user can opt
 *             in to treating an outage as an override in Settings.
 */
enum class OverrideStatus { ACTIVE, INACTIVE, UNKNOWN }

@Singleton
class KillSwitch @Inject constructor(
    private val client: HomeAssistantClient,
) {
    /**
     * MUST be called at the moment of blocking, not cached at startup.
     * That is the whole point of a *remote* kill switch.
     */
    suspend fun check(settings: AnchorSettings): OverrideStatus {
        if (!settings.killSwitchEnabled) return OverrideStatus.INACTIVE
        if (settings.killSwitchEntityId.isBlank()) return OverrideStatus.INACTIVE

        return when (
            val result = client.fetchState(
                baseUrl = settings.haBaseUrl,
                token = settings.haToken,
                entityId = settings.killSwitchEntityId.trim(),
            )
        ) {
            is HaResult.Unavailable -> OverrideStatus.UNKNOWN
            is HaResult.Ok ->
                if (result.state.state.trim()
                        .equals(settings.killSwitchOverrideState.trim(), ignoreCase = true)
                ) OverrideStatus.ACTIVE else OverrideStatus.INACTIVE
        }
    }

    /**
     * @param settings needed for [AnchorSettings.killSwitchFailOpenOnOutage];
     *   taking it as a parameter means no caller can forget the flag exists.
     */
    fun isBlockingDisabled(status: OverrideStatus, settings: AnchorSettings): Boolean =
        status == OverrideStatus.ACTIVE ||
            (status == OverrideStatus.UNKNOWN && settings.killSwitchFailOpenOnOutage)

    /** Convenience for the common "check then decide" pair. */
    suspend fun blockingDisabled(settings: AnchorSettings): Boolean =
        isBlockingDisabled(check(settings), settings)
}
