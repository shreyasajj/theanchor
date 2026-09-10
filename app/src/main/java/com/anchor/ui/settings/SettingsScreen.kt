package com.anchor.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anchor.data.db.Phase
import com.anchor.ui.components.Eyebrow
import com.anchor.ui.settings.sections.AppsSection
import com.anchor.ui.settings.sections.ExportSection
import com.anchor.ui.settings.sections.HomeAssistantSection
import com.anchor.ui.settings.sections.LimitsSection
import com.anchor.ui.settings.sections.QuestionsSection
import com.anchor.ui.settings.sections.ScheduleSection

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    installedApps: InstalledAppsRepository,
    onPickExportFolder: () -> Unit,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsState()
    val morningQuestions by viewModel.morningQuestions.collectAsState()
    val eveningQuestions by viewModel.eveningQuestions.collectAsState()
    val limits by viewModel.appLimits.collectAsState()

    var apps by remember { mutableStateOf(emptyList<InstalledApp>()) }
    LaunchedEffect(Unit) { apps = installedApps.launchableApps() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp),
        ) {
            item { Group("Rhythm") }
            item { ScheduleSection(settings, viewModel::updateSettings) }

            item { Group("Apps") }
            item { AppsSection(
                title = "Blocked in the evening",
                description = "Opening one of these during the evening window triggers the check-in.",
                apps = apps,
                selected = settings.blockedPackages,
                onToggle = viewModel::toggleBlockedApp,
            ) }
            item { AppsSection(
                title = "Morning allowlist",
                description = "Never interrupted during the morning lockdown. The dialer, " +
                    "messaging, emergency and system apps are always allowed.",
                apps = apps,
                selected = settings.allowlistPackages,
                onToggle = viewModel::toggleAllowlistApp,
            ) }
            item { LimitsSection(
                apps = apps,
                limits = limits,
                showRelockBubble = settings.showRelockBubble,
                onSetRelockBubble = { on -> viewModel.updateSettings { it.copy(showRelockBubble = on) } },
                onSetLimit = viewModel::setLimit,
                onClearLimit = viewModel::clearLimit,
            ) }

            item { Group("Questions") }
            item { QuestionsSection(
                title = "Morning",
                description = "Asked during the lockdown. Edits keep past answers attached.",
                questions = morningQuestions,
                onAdd = { viewModel.addQuestion(Phase.MORNING, it) },
                onEdit = viewModel::editQuestion,
                onDelete = viewModel::deleteQuestion,
                onMove = viewModel::moveQuestion,
            ) }
            item { QuestionsSection(
                title = "Evening",
                description = "Asked before a blocked app opens at night.",
                questions = eveningQuestions,
                onAdd = { viewModel.addQuestion(Phase.EVENING, it) },
                onEdit = viewModel::editQuestion,
                onDelete = viewModel::deleteQuestion,
                onMove = viewModel::moveQuestion,
            ) }

            item { Group("Home Assistant") }
            item { HomeAssistantSection(
                settings = settings,
                onChange = viewModel::updateSettings,
                onSetRooms = viewModel::setRooms,
                onSetLocationMode = viewModel::setLocationMode,
            ) }

            item { Group("Notes") }
            item { ExportSection(
                settings = settings,
                onPickFolder = onPickExportFolder,
                onChange = viewModel::updateSettings,
            ) }
        }
    }
}

@Composable
private fun Group(title: String) {
    Eyebrow(
        title,
        Modifier.padding(top = 24.dp, bottom = 6.dp, start = 4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
