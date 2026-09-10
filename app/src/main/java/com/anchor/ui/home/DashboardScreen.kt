package com.anchor.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anchor.data.db.DailyLog
import com.anchor.data.ha.OverrideStatus
import com.anchor.ui.components.AnchorCard
import com.anchor.ui.components.Eyebrow
import com.anchor.ui.components.Hint
import com.anchor.ui.components.Meter
import com.anchor.ui.components.Pill
import com.anchor.ui.components.PillTone
import com.anchor.ui.components.SectionCard
import com.anchor.ui.components.SoftDivider
import com.anchor.ui.components.StatusDot
import com.anchor.ui.settings.sections.formatMinute
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onOpenSettings: () -> Unit,
    onFixPermission: (String) -> Unit,
    onAnswerMorning: () -> Unit,
    onAnswerEvening: () -> Unit,
    onMeditate: () -> Unit,
) {
    val setup = state.permissions?.takeIf { !it.isFullyConfigured }
    // A Surface, not a bare LazyColumn: it paints the background and sets the
    // content colour, without which text outside the cards renders black.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
    // LazyColumn so each card is laid out once and reused, rather than the
    // whole page being measured on every frame of a scroll.
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "header") { Header(state, onOpenSettings) }
        if (state.overrideStatus == OverrideStatus.ACTIVE) item(key = "override") { OverrideBanner() }
        if (setup != null) item(key = "setup") { SetupCard(setup, onFixPermission) }
        item(key = "today") { TodayCard(state, onAnswerMorning, onAnswerEvening, onMeditate) }
        if (state.usage.isNotEmpty()) item(key = "limits") { LimitsCard(state.usage) }
        item(key = "week") { WeekCard(state.week) }
        item(key = "status") { StatusCard(state) }
    }
    }
}

@Composable
private fun Header(state: DashboardUiState, onOpenSettings: () -> Unit) {
    val today = state.week.lastOrNull()?.date?.let { LocalDate.parse(it) }
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Eyebrow("The Anchor")
            Spacer(Modifier.height(6.dp))
            Text(
                text = today?.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())) ?: "",
                style = MaterialTheme.typography.headlineLarge,
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                Icons.Outlined.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OverrideBanner() {
    AnchorCard(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
    ) {
        Eyebrow("Override active", color = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.height(6.dp))
        Text(
            "Blocking is disabled from Home Assistant. Flip the switch back to resume.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun SetupCard(permissions: PermissionState, onFix: (String) -> Unit) {
    SectionCard(title = "Setup", trailing = { Pill("${permissions.missing.size} left", PillTone.ACCENT) }) {
        if (!permissions.canEnforce) {
            Hint("Nothing is enforced until the first two are granted.", Modifier.padding(bottom = 8.dp))
        }
        permissions.missing.forEachIndexed { index, item ->
            if (index > 0) SoftDivider()
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(item, style = MaterialTheme.typography.bodyLarge)
                    Hint(PermissionState.reason(item))
                }
                TextButton(onClick = { onFix(item) }) { Text("Grant") }
            }
        }
    }
}

@Composable
private fun TodayCard(
    state: DashboardUiState,
    onAnswerMorning: () -> Unit,
    onAnswerEvening: () -> Unit,
    onMeditate: () -> Unit,
) {
    val s = state.settings
    SectionCard(title = "Today") {
        PhaseRow(
            name = "Morning",
            window = "${formatMinute(s.morningStartMinute)} – ${formatMinute(s.morningEndMinute)}",
            done = state.today?.morningCompletedAt != null,
            preview = state.today?.let { firstAnswer(it, morning = true) },
            onAnswer = onAnswerMorning,
        )
        SoftDivider(Modifier.padding(vertical = 12.dp))
        PhaseRow(
            name = "Evening",
            window = "${formatMinute(s.eveningStartMinute)} – ${formatMinute(s.eveningEndMinute)}",
            done = state.today?.eveningCompletedAt != null,
            preview = state.today?.let { firstAnswer(it, morning = false) },
            onAnswer = onAnswerEvening,
        )
        SoftDivider(Modifier.padding(vertical = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(state.meditationSecondsToday > 0, size = 12)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Breathing", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (state.meditationSecondsToday > 0) {
                        val minutes = state.meditationSecondsToday / 60
                        val sits = state.meditationCountToday
                        "$minutes min over ${if (sits == 1) "one sit" else "$sits sits"}"
                    } else "Not yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onMeditate) { Text("Sit now") }
        }
        Hint(
            "The morning check-in appears on its own during the window when you are home. " +
                "Answer now to do it by hand at any time.",
            Modifier.padding(top = 12.dp),
        )
    }
}

private fun formatUnits(units: Double): String =
    if (units == units.toLong().toDouble()) units.toLong().toString() else "%.1f".format(units)

private fun firstAnswer(log: DailyLog, morning: Boolean): String? =
    if (morning) log.mission ?: log.avoiding else log.led ?: log.softened ?: log.faked

@Composable
private fun PhaseRow(name: String, window: String, done: Boolean, preview: String?, onAnswer: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(done, size = 12)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(10.dp))
                Text(window, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Text(
                text = if (done) preview ?: "Done" else "Not yet",
                style = MaterialTheme.typography.bodyMedium,
                color = if (done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (done) Pill("Done", PillTone.GOOD) else TextButton(onClick = onAnswer) { Text("Answer now") }
    }
}

@Composable
private fun LimitsCard(rows: List<UsageRow>) {
    SectionCard(title = "Today's limits") {
        rows.forEachIndexed { index, row ->
            if (index > 0) Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(row.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                val time = row.limitMinutes?.let { "${row.usedMinutes} / $it min" } ?: "${row.usedMinutes} min"
                val opens = row.limitOpens?.let { "  ·  ${formatUnits(row.openUnits)} / $it opens" } ?: ""
                Text(
                    text = time + opens,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.isExhausted) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            row.timeFraction?.let { fraction ->
                Spacer(Modifier.height(8.dp))
                Meter(fraction = fraction, exhausted = row.isExhausted)
            }
        }
    }
}

@Composable
private fun WeekCard(week: List<DayMark>) {
    SectionCard(title = "Last seven days") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            week.forEach { day ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        day.weekdayInitial,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (day.isToday) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    StatusDot(day.morningDone)
                    Spacer(Modifier.height(6.dp))
                    StatusDot(day.eveningDone)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Hint("Top dot: morning. Bottom dot: evening.")
    }
}

@Composable
private fun StatusCard(state: DashboardUiState) {
    val s = state.settings
    SectionCard(title = "Status") {
        StatusLine("Home Assistant", if (s.homeAssistantConfigured) "Configured" else "Not configured", s.homeAssistantConfigured)
        SoftDivider(Modifier.padding(vertical = 8.dp))
        StatusLine(
            "Kill switch",
            when {
                !s.killSwitchEnabled -> "Off"
                state.overrideStatus == OverrideStatus.ACTIVE -> "Override active"
                state.overrideStatus == OverrideStatus.UNKNOWN -> "Unreachable"
                else -> "Armed"
            },
            good = s.killSwitchEnabled && state.overrideStatus == OverrideStatus.INACTIVE,
        )
        SoftDivider(Modifier.padding(vertical = 8.dp))
        StatusLine("Blocked apps", "${s.blockedPackages.size}", s.blockedPackages.isNotEmpty())
        SoftDivider(Modifier.padding(vertical = 8.dp))
        StatusLine("Export folder", if (s.exportTreeUri != null) "Chosen" else "Not set", s.exportTreeUri != null)
    }
}

@Composable
private fun StatusLine(label: String, value: String, good: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (good) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
