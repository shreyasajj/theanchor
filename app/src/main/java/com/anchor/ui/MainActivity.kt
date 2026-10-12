package com.anchor.ui

import android.Manifest
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.anchor.data.db.CustomQuestionDao
import com.anchor.data.db.DailyLogDao
import com.anchor.data.ha.KillSwitch
import com.anchor.data.location.PhoneLocationSource
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.SettingsRepository
import com.anchor.data.usage.AppLimitDao
import com.anchor.data.usage.MeditationSessionDao
import com.anchor.domain.AnchorDate
import com.anchor.domain.LimitGate
import com.anchor.domain.LockdownEnforcer
import com.anchor.ui.lock.EveningLockActivity
import com.anchor.ui.lock.MeditationActivity
import com.anchor.service.AnchorAccessibilityService
import com.anchor.service.AnchorForegroundService
import com.anchor.service.MorningAlarmScheduler
import com.anchor.ui.home.DashboardScreen
import com.anchor.ui.home.DashboardViewModel
import com.anchor.ui.home.PermissionState
import com.anchor.ui.settings.InstalledAppsRepository
import com.anchor.ui.settings.PlaceTarget
import com.anchor.ui.settings.SettingsScreen
import com.anchor.ui.settings.SettingsViewModel
import com.anchor.ui.theme.AnchorTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/** How often the dashboard re-reads usage while it is in front. */
private const val DASHBOARD_TICK_MILLIS = 5_000L

private val FOREGROUND_LOCATION = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var questionDao: CustomQuestionDao
    @Inject lateinit var dailyLogDao: DailyLogDao
    @Inject lateinit var appLimitDao: AppLimitDao
    @Inject lateinit var meditationDao: MeditationSessionDao
    @Inject lateinit var anchorDate: AnchorDate
    @Inject lateinit var killSwitch: KillSwitch
    @Inject lateinit var limitGate: LimitGate
    @Inject lateinit var enforcer: LockdownEnforcer
    @Inject lateinit var scheduler: MorningAlarmScheduler
    @Inject lateinit var installedApps: InstalledAppsRepository
    @Inject lateinit var phoneLocation: PhoneLocationSource

    /** The place waiting on a location permission answer; lost on recreation, which only means tapping again. */
    private var pendingPlace: PlaceTarget? = null

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
                    currentFix = { phoneLocation.current(freshOnly = true) },
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
                    meditationDao = meditationDao,
                    appLabels = { installedApps.launchableApps().associate { it.packageName to it.label } },
                    readPermissions = { settings -> readPermissions(settings) },
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

                // Android grants "all the time" only as a second step after
                // foreground location, and shows it as a settings page.
                val backgroundLocationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { dashboardViewModel.refresh() }

                val foregroundLocationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { granted ->
                    if (granted.values.any { it }) {
                        backgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    } else {
                        dashboardViewModel.refresh()
                    }
                }

                val placeLocationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { granted ->
                    val target = pendingPlace ?: return@rememberLauncherForActivityResult
                    pendingPlace = null
                    if (granted.values.any { it }) settingsViewModel.capturePlace(target)
                    else settingsViewModel.reportLocationDenied()
                }

                NavHost(navController = navController, startDestination = "dashboard") {
                    composable("dashboard") {
                        val state by dashboardViewModel.state.collectAsState()
                        // Refresh on every return to the foreground, then keep
                        // the usage figures current while the screen is up.
                        val lifecycle = LocalLifecycleOwner.current.lifecycle
                        LaunchedEffect(lifecycle) {
                            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                                dashboardViewModel.refresh()
                                while (true) {
                                    delay(DASHBOARD_TICK_MILLIS)
                                    dashboardViewModel.tick()
                                }
                            }
                        }
                        DashboardScreen(
                            state = state,
                            onOpenSettings = { navController.navigate("settings") },
                            onAnswerMorning = { enforcer.begin() },
                            onAnswerEvening = { startActivity(Intent(this@MainActivity, EveningLockActivity::class.java)) },
                            onMeditate = { startActivity(MeditationActivity.intent(this@MainActivity)) },
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
                                    PermissionState.LOCATION ->
                                        if (hasForegroundLocation()) {
                                            backgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                        } else {
                                            foregroundLocationPermission.launch(FOREGROUND_LOCATION)
                                        }
                                }
                            },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            installedApps = installedApps,
                            onPickExportFolder = { folderPicker.launch(null) },
                            onCapturePlace = { target ->
                                if (hasForegroundLocation()) {
                                    settingsViewModel.capturePlace(target)
                                } else {
                                    pendingPlace = target
                                    placeLocationPermission.launch(FOREGROUND_LOCATION)
                                }
                            },
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

    private fun readPermissions(settings: AnchorSettings) = PermissionState(
        accessibilityEnabled = isAccessibilityServiceEnabled(),
        overlayGranted = Settings.canDrawOverlays(this),
        usageStatsGranted = hasUsageStatsPermission(),
        notificationsGranted = granted(Manifest.permission.POST_NOTIFICATIONS),
        exportFolderChosen = settings.exportTreeUri != null && runCatching {
            contentResolver.persistedUriPermissions.any {
                it.uri.toString() == settings.exportTreeUri && it.isWritePermission
            }
        }.getOrDefault(false),
        locationNeeded = settings.usesPhoneLocation,
        locationGranted = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
    )

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasForegroundLocation(): Boolean = FOREGROUND_LOCATION.any { granted(it) }

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
