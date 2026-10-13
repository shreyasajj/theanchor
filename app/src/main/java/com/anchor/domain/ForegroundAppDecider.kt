package com.anchor.domain

import com.anchor.data.settings.AnchorSettings

sealed interface ForegroundAction {
    /** Leave the foreground app alone. */
    data object Ignore : ForegroundAction

    /** A lockdown (morning questions or evening sit) is up and something escaped it; bring it back. */
    data object ReassertLock : ForegroundAction

    /** Hand this package to the limit and evening gates. */
    data class EvaluateEvening(val packageName: String) : ForegroundAction
}

/**
 * Pure classification of a foreground-app change.
 *
 * The allowlist here is a safety feature, not a convenience: during a
 * lockdown the user must always be able to place a call, send a message, and
 * reach system surfaces. Anything ambiguous resolves to [ForegroundAction.Ignore].
 */
object ForegroundAppDecider {

    private const val OWN_PACKAGE = "com.anchor"

    /**
     * Never interrupted, regardless of settings. Matched as prefixes so OEM
     * variants (com.samsung.android.incallui, com.android.server.telecom, …)
     * are covered too.
     */
    val ALWAYS_ALLOWED_PREFIXES: Set<String> = setOf(
        "com.android.systemui",
        "com.android.incallui",
        "com.android.server.telecom",
        "com.android.phone",
        "com.android.dialer",
        "com.android.emergency",
        "com.android.settings",      // so the user can always reach Settings
        "android",
    )

    fun decide(
        packageName: String,
        lockActive: Boolean,
        settings: AnchorSettings,
    ): ForegroundAction {
        if (packageName.isBlank()) return ForegroundAction.Ignore
        if (packageName == OWN_PACKAGE) return ForegroundAction.Ignore
        if (isAlwaysAllowed(packageName)) return ForegroundAction.Ignore
        if (packageName in settings.allowlistPackages) return ForegroundAction.Ignore

        return if (lockActive) {
            ForegroundAction.ReassertLock
        } else {
            ForegroundAction.EvaluateEvening(packageName)
        }
    }

    /**
     * Drawn over an app without leaving it: the notification shade, the lock
     * screen, system dialogs.
     */
    private val OVERLAYS: Set<String> = setOf("com.android.systemui", "android")

    /**
     * Whether [packageName] reaching the front means the user left the app
     * they were in. True for ordinary apps and also for those that are never
     * blocked, the dialer, Messages, Settings and the in-call screen: never
     * blocking them is a safety rule, not a sign the user stayed. False for
     * overlays and for our own screens.
     */
    fun leavesTheAppInFront(packageName: String): Boolean {
        if (packageName.isBlank() || packageName == OWN_PACKAGE) return false
        return OVERLAYS.none { packageName == it || packageName.startsWith("$it.") }
    }

    /** Prefix match with a dot boundary: `com.android.dialer.foo` yes, `com.android.dialerapp` no. */
    fun isAlwaysAllowed(packageName: String): Boolean =
        ALWAYS_ALLOWED_PREFIXES.any { packageName == it || packageName.startsWith("$it.") }

    /**
     * Seeds the user's allowlist with the device's actual default dialer and
     * SMS apps, which vary by OEM. Nulls (unresolvable) are simply dropped.
     */
    fun defaultAllowlist(dialer: String?, sms: String?): Set<String> =
        listOfNotNull(dialer, sms).filter { it.isNotBlank() }.toSet()
}
