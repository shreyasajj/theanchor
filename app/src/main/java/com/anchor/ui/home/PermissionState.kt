package com.anchor.ui.home

/**
 * What the user still has to grant. Ordered as the onboarding presents them.
 */
data class PermissionState(
    val accessibilityEnabled: Boolean,
    val overlayGranted: Boolean,
    val usageStatsGranted: Boolean,
    val notificationsGranted: Boolean,
    val exportFolderChosen: Boolean,
    /** The setup reads the phone's location (phone source or vacation mode). */
    val locationNeeded: Boolean = false,
    /** "Allow all the time": gates run from the background. */
    val locationGranted: Boolean = true,
) {
    /** The minimum needed to block anything at all. */
    val canEnforce: Boolean get() = accessibilityEnabled && overlayGranted

    val isFullyConfigured: Boolean get() = missing.isEmpty()

    val missing: List<String>
        get() = buildList {
            if (!accessibilityEnabled) add(ACCESSIBILITY)
            if (!overlayGranted) add(OVERLAY)
            if (!usageStatsGranted) add(USAGE)
            if (!notificationsGranted) add(NOTIFICATIONS)
            if (locationNeeded && !locationGranted) add(LOCATION)
            if (!exportFolderChosen) add(EXPORT_FOLDER)
        }

    companion object {
        const val ACCESSIBILITY = "Accessibility service"
        const val OVERLAY = "Display over other apps"
        const val USAGE = "Usage access"
        const val NOTIFICATIONS = "Notifications"
        const val EXPORT_FOLDER = "Markdown export folder"
        const val LOCATION = "Location (all the time)"

        /** Why each item matters, shown under it on the dashboard. */
        fun reason(item: String): String = when (item) {
            ACCESSIBILITY -> "Lets The Anchor notice which app is in front. Nothing works without it."
            OVERLAY -> "Needed to bring a lock screen up from the background."
            USAGE -> "Powers per-app time and open-count limits."
            NOTIFICATIONS -> "For the quiet status notification that keeps the service alive."
            EXPORT_FOLDER -> "Where the daily Markdown files are written."
            LOCATION -> "Checks where you are from the background when the phone is the location source."
            else -> ""
        }
    }
}
