package com.anchor.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.anchor.data.settings.SettingsRepository
import com.anchor.data.usage.AppLimitDao
import com.anchor.data.usage.MeditationSessionDao
import com.anchor.domain.AnchorDate
import com.anchor.domain.LimitGate
import com.anchor.domain.LockdownEnforcer
import com.anchor.ui.lock.EveningLockActivity
import com.anchor.ui.lock.MeditationActivity
import com.anchor.service.AnchorForegroundService
import com.anchor.service.MorningAlarmScheduler
import com.anchor.ui.home.DashboardScreen
import com.anchor.ui.home.DashboardViewModel
import com.anchor.ui.home.LocationAsk
import com.anchor.ui.home.LocationPermissionFlow
import com.anchor.ui.home.PermissionReader
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

    private val permissions by lazy { PermissionReader(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AnchorForegroundService.start(this)
        rescheduleMorningAlarm()

        // Locals, not fields: the ViewModels outlive this Activity across a
        // rotation, and a lambda that read a field would keep it alive.
        val alarm = scheduler
        val location = phoneLocation
        val apps = installedApps
        val reader = permissions

        val settingsViewModel = ViewModelProvider(
            this,
            factory {
                SettingsViewModel(
                    settingsRepository = settingsRepository,
                    questionDao = questionDao,
                    appLimitDao = appLimitDao,
                    onScheduleChanged = { settings -> alarm.schedule(settings) },
                    currentFix = { location.current(freshOnly = true) },
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
                    appLabels = { apps.launchableApps().associate { it.packageName to it.label } },
                    readPermissions = { settings -> reader.read(settings) },
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
                ) { ok ->
                    if (deniedForGood(Manifest.permission.ACCESS_BACKGROUND_LOCATION, ok)) openAppSettings()
                    dashboardViewModel.refresh()
                }

                val foregroundLocationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { granted ->
                    // Only precise is worth going on to "all the time" for.
                    if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                        backgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    } else {
                        if (deniedForGood(Manifest.permission.ACCESS_FINE_LOCATION, false)) openAppSettings()
                        dashboardViewModel.refresh()
                    }
                }

                // Save the place, then ask for "all the time": without it the
                // gates, which run in the background, never get a fix.
                val capture: (PlaceTarget) -> Unit = { target ->
                    settingsViewModel.capturePlace(target, onSaved = {
                        if (nextLocationAsk() == LocationAsk.BACKGROUND) {
                            backgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }
                    })
                }

                val placeLocationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { granted ->
                    val target = pendingPlace ?: return@rememberLauncherForActivityResult
                    pendingPlace = null
                    when {
                        granted[Manifest.permission.ACCESS_FINE_LOCATION] == true -> capture(target)
                        granted.values.any { it } -> settingsViewModel.reportPreciseLocationNeeded()
                        else -> {
                            settingsViewModel.reportLocationDenied()
                            if (deniedForGood(Manifest.permission.ACCESS_FINE_LOCATION, false)) openAppSettings()
                        }
                    }
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
                                    PermissionState.LOCATION -> when (nextLocationAsk()) {
                                        LocationAsk.PRECISE -> foregroundLocationPermission.launch(FOREGROUND_LOCATION)
                                        LocationAsk.BACKGROUND ->
                                            backgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                        LocationAsk.NONE -> dashboardViewModel.refresh()
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
                                if (nextLocationAsk() != LocationAsk.PRECISE) {
                                    capture(target)
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

    private fun nextLocationAsk(): LocationAsk = permissions.nextLocationAsk()

    /** True when Android will not show [permission]'s request again after this denial. */
    private fun deniedForGood(permission: String, granted: Boolean): Boolean =
        LocationPermissionFlow.needsAppSettings(granted, shouldShowRequestPermissionRationale(permission))

    /** The only place a permission denied for good can still be granted. */
    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    /** Builds a one-off ViewModel factory. */
    private fun <T : ViewModel> factory(build: () -> T) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <V : ViewModel> create(modelClass: Class<V>): V = build() as V
    }
}
