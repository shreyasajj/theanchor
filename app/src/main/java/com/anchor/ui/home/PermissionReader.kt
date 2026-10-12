package com.anchor.ui.home

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.anchor.data.settings.AnchorSettings
import com.anchor.service.AnchorAccessibilityService

/**
 * Reads what has been granted. Holds the application context, never an
 * Activity, because the dashboard ViewModel keeps it across rotations.
 */
class PermissionReader(private val context: Context) {

    fun read(settings: AnchorSettings) = PermissionState(
        accessibilityEnabled = isAccessibilityServiceEnabled(),
        overlayGranted = Settings.canDrawOverlays(context),
        usageStatsGranted = hasUsageStatsPermission(),
        notificationsGranted = granted(Manifest.permission.POST_NOTIFICATIONS),
        exportFolderChosen = settings.exportTreeUri != null && runCatching {
            context.contentResolver.persistedUriPermissions.any {
                it.uri.toString() == settings.exportTreeUri && it.isWritePermission
            }
        }.getOrDefault(false),
        locationNeeded = settings.usesPhoneLocation,
        locationGranted = nextLocationAsk() == LocationAsk.NONE,
    )

    fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun nextLocationAsk(): LocationAsk = LocationPermissionFlow.next(
        precise = granted(Manifest.permission.ACCESS_FINE_LOCATION),
        background = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
    )

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "${context.packageName}/${AnchorAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
