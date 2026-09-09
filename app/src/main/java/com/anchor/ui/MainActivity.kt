package com.anchor.ui

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.anchor.data.db.CustomQuestionDao
import com.anchor.data.db.DailyLogDao
import com.anchor.data.ha.KillSwitch
import com.anchor.data.settings.SettingsRepository
import com.anchor.data.usage.AppLimitDao
import com.anchor.domain.AnchorDate
import com.anchor.domain.LimitGate
import com.anchor.service.AnchorAccessibilityService
import com.anchor.service.AnchorForegroundService
import com.anchor.service.MorningAlarmScheduler
import com.anchor.ui.home.DashboardScreen
import com.anchor.ui.home.DashboardViewModel
import com.anchor.ui.home.PermissionState
import com.anchor.ui.settings.InstalledAppsRepository
import com.anchor.ui.settings.SettingsScreen
import com.anchor.ui.settings.SettingsViewModel
import com.anchor.ui.theme.AnchorTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var questionDao: CustomQuestionDao
    @Inject lateinit var dailyLogDao: DailyLogDao
    @Inject lateinit var appLimitDao: AppLimitDao
    @Inject lateinit var anchorDate: AnchorDate
    @Inject lateinit var killSwitch: KillSwitch
    @Inject lateinit var limitGate: LimitGate
    @Inject lateinit var scheduler: MorningAlarmScheduler
    @Inject lateinit var installedApps: InstalledAppsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AnchorForegroundService.start(this)
        rescheduleMorningAlarm()

        val settingsViewModel = ViewModelProvider(
            this,
            factory {
                SettingsViewModel(
                    settingsRepository = settingsRepository,
                    questionDao = questionDao,
                    appLimitDao = appLimitDao,
                    onScheduleChanged = { rescheduleMorningAlarm() },
                )
            },
        )[SettingsViewModel::class.java]

        val dashboardViewModel = ViewModelProvider(
            this,
            factory {
                DashboardViewModel(
                    dailyLogDao = dailyLogDao,
                    anchorDate = anchorDate,
                    killSwitch = killSwitch,
                    settingsRepository = settingsRepository,
                    limitGate = limitGate,
                    appLimitDao = appLimitDao,
                    appLabels = { installedApps.launchableApps().associate { it.packageName to it.label } },
                    readPermissions = { treeUri -> readPermissions(treeUri) },
                )
            },
        )[DashboardViewModel::class.java]

        setContent {
            AnchorTheme {
                val navController = rememberNavController()

                val folderPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree()
                ) { uri: Uri? ->
                    if (uri != null) {
                        // Persist across reboots; without this the URI is
                        // useless the next morning.
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                        settingsViewModel.setExportTree(uri.toString())
                        dashboardViewModel.refresh()
                    }
                }

                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { dashboardViewModel.refresh() }

                NavHost(navController = navController, startDestination = "dashboard") {
                    composable("dashboard") {
                        val state by dashboardViewModel.state.collectAsState()
                        LaunchedEffect(Unit) { dashboardViewModel.refresh() }
                        DashboardScreen(
                            state = state,
                            onOpenSettings = { navController.navigate("settings") },
                            onFixPermission = { item ->
                                when (item) {
                                    PermissionState.ACCESSIBILITY ->
                                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    PermissionState.OVERLAY -> startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:$packageName"),
                                        )
                                    )
                                    PermissionState.USAGE ->
                                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                    PermissionState.NOTIFICATIONS -> notificationPermission.launch(
                                        android.Manifest.permission.POST_NOTIFICATIONS
                                    )
                                    PermissionState.EXPORT_FOLDER -> folderPicker.launch(null)
                                }
                            },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            installedApps = installedApps,
                            onPickExportFolder = { folderPicker.launch(null) },
                            onBack = {
                                dashboardViewModel.refresh()
                                navController.popBackStack()
                            },
                        )
                    }
                }
            }
        }
    }

    private fun rescheduleMorningAlarm() {
        lifecycleScope.launch { scheduler.schedule(settingsRepository.current()) }
    }

    private fun readPermissions(exportTreeUri: String?) = PermissionState(
        accessibilityEnabled = isAccessibilityServiceEnabled(),
        overlayGranted = Settings.canDrawOverlays(this),
        usageStatsGranted = hasUsageStatsPermission(),
        notificationsGranted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED,
        exportFolderChosen = exportTreeUri != null && runCatching {
            contentResolver.persistedUriPermissions.any { it.uri.toString() == exportTreeUri && it.isWritePermission }
        }.getOrDefault(false),
    )

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${AnchorAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Builds a one-off ViewModel factory. */
    private fun <T : ViewModel> factory(build: () -> T) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <V : ViewModel> create(modelClass: Class<V>): V = build() as V
    }
}
