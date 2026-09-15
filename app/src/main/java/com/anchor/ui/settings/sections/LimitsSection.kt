package com.anchor.ui.settings.sections

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.EnforcementMode
import com.anchor.data.usage.AppLimit
import com.anchor.data.usage.LimitMode
import com.anchor.ui.components.DraftTextField
import com.anchor.ui.components.Hint
import com.anchor.ui.components.SettingRow
import com.anchor.ui.components.SoftDivider
import com.anchor.ui.components.anchorTextFieldColors
import com.anchor.ui.settings.InstalledApp

/** One-line description of a configured limit, for the list row. */
object LimitSummary {
    fun describe(limit: AppLimit): String {
        val parts = buildList {
            limit.effectiveDailyMinutes?.let { add("$it min/day") }
            limit.effectiveDailyOpens?.let { add("$it opens") }
            limit.cooldownMinutes?.let { add("$it min cooldown") }
            limit.sessionMinutes?.let { add("$it min sessions") }
            if (limit.preOpenDelaySeconds > 0) add("${limit.preOpenDelaySeconds}s pause")
        }
        val body = if (parts.isEmpty()) "No limits set" else parts.joinToString(" · ")
        return window(limit)?.let { "$it · $body" } ?: body
    }

    /** "14:00–17:00", or null for an all-day limit. */
    fun window(limit: AppLimit): String? {
        val start = limit.windowStartMinute ?: return null
        val end = limit.windowEndMinute ?: return null
        return "${formatMinute(start)}–${formatMinute(end)}"
    }

    /**
     * Apps the picker may offer for [target]: not in any other limit that is
     * in force at the same time. An app can be in two limits only when their
     * windows never overlap.
     */
    fun availableFor(target: AppLimit, others: List<AppLimit>, apps: List<InstalledApp>): List<InstalledApp> {
        val taken = others
            .filter { it.id != target.id && it.overlapsInTime(target) }
            .flatMap { it.packages }
            .toSet()
        return apps.filter { it.packageName !in taken || it.packageName in target.packages }
    }

    /** The row title: the group's name, or its apps. */
    fun title(limit: AppLimit, labels: Map<String, String>): String {
        limit.name.trim().takeIf { it.isNotEmpty() }?.let { return it }
        val names = limit.packages.map { labels[it] ?: it }.sortedBy { it.lowercase() }
        return if (names.size <= 2) names.joinToString(" & ") else "${names[0]}, ${names[1]} +${names.size - 2}"
    }
}

@Composable
fun LimitsSection(
    apps: List<InstalledApp>,
    limits: List<AppLimit>,
    settings: AnchorSettings,
    onChange: ((AnchorSettings) -> AnchorSettings) -> Unit,
    onUpdateLimit: (Long, (AppLimit) -> AppLimit) -> Unit,
    onCreateLimit: (Set<String>) -> Unit,
    onSetLimitPackages: (Long, Set<String>) -> Unit,
    onDeleteLimit: (Long) -> Unit,
) {
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var newPickerOpen by remember { mutableStateOf(false) }
    var membersPickerFor by remember { mutableStateOf<AppLimit?>(null) }
    val labels = remember(apps) { apps.associate { it.packageName to it.label } }

    SettingsSection(title = "App limits") {
        Hint(
            "Limits are independent of the Evening Anchor. Put several apps in one limit and they " +
                "share it: minutes in one count for all, and switching between them is the same open. " +
                "Give a limit hours and it applies only then, so one app can have a different " +
                "budget at different times of day. Leave a field empty for no limit of that kind.",
            Modifier.padding(bottom = 6.dp),
        )

        limits.forEachIndexed { index, limit ->
            val expanded = expandedId == limit.id
            if (index > 0) SoftDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expandedId = if (expanded) null else limit.id }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(LimitSummary.title(limit, labels), style = MaterialTheme.typography.bodyLarge)
                    Hint(LimitSummary.describe(limit))
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    LimitEditor(
                        limit = limit,
                        labels = labels,
                        onUpdate = { transform -> onUpdateLimit(limit.id, transform) },
                        onChangeApps = { membersPickerFor = limit },
                    )
                    TextButton(
                        onClick = {
                            onDeleteLimit(limit.id)
                            expandedId = null
                        },
                    ) { Text("Remove this limit", color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        TextButton(onClick = { newPickerOpen = true }, modifier = Modifier.padding(top = 4.dp)) {
            Text("Add a limit")
        }

        SoftDivider(Modifier.padding(top = 8.dp))
        SettingRow(
            "Floating lock button",
            supporting = "A small button inside limited apps for ending the session early. " +
                "It fades out of the way when you leave it alone, and can be dragged anywhere.",
        ) {
            Switch(
                checked = settings.showRelockBubble,
                onCheckedChange = { on -> onChange { it.copy(showRelockBubble = on) } },
            )
        }
    }

    SettingsSection(title = "When a limit is reached") {
        RadioGroup(
            options = EnforcementMode.entries,
            selected = settings.enforcementMode,
            label = {
                when (it) {
                    EnforcementMode.STRICT -> "Block the app"
                    EnforcementMode.STREAK -> "Let me through, but end my streak"
                }
            },
            supporting = {
                when (it) {
                    EnforcementMode.STRICT -> "The app stays shut until the limit resets."
                    EnforcementMode.STREAK -> "The blocked screen offers a way in. Taking it waives that " +
                        "limit until the next reset and restarts the streak tomorrow."
                }
            },
            onPick = { mode -> onChange { it.copy(enforcementMode = mode) } },
        )
        Hint(
            "The streak counts consecutive days on which you never walked through a limit. " +
                "It is shown on the dashboard while this mode is on.",
            Modifier.padding(top = 4.dp),
        )
    }

    if (newPickerOpen) {
        MultiAppPicker(
            title = "Apps for the new limit",
            // A new limit is all day until its hours are set, so anything
            // already limited is hidden. Set the hours, then add the app.
            apps = LimitSummary.availableFor(AppLimit(), limits, apps),
            initial = emptySet(),
            hint = "Apps already in a limit are hidden. To limit an app differently at different " +
                "times, create the limit, set its hours, then add the app to it.",
            onDone = { chosen ->
                newPickerOpen = false
                if (chosen.isNotEmpty()) onCreateLimit(chosen)
            },
            onDismiss = { newPickerOpen = false },
        )
    }

    membersPickerFor?.let { limit ->
        MultiAppPicker(
            title = "Apps in this limit",
            apps = LimitSummary.availableFor(limit, limits, apps),
            initial = limit.packages,
            hint = if (limit.isAllDay) "Apps in another limit are hidden."
            else "Apps limited at an overlapping time are hidden.",
            onDone = { chosen ->
                membersPickerFor = null
                onSetLimitPackages(limit.id, chosen)
            },
            onDismiss = { membersPickerFor = null },
        )
    }
}

/** The picker with its own selection, committed on Done. */
@Composable
private fun MultiAppPicker(
    title: String,
    apps: List<InstalledApp>,
    initial: Set<String>,
    onDone: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    hint: String? = null,
) {
    var selected by remember { mutableStateOf(initial) }
    AppPickerDialog(
        title = title,
        apps = apps,
        hint = hint,
        selected = selected,
        onToggle = { pkg -> selected = if (pkg in selected) selected - pkg else selected + pkg },
        onDismiss = { onDone(selected) },
        onCancel = onDismiss,
    )
}

@Composable
private fun LimitEditor(
    limit: AppLimit,
    labels: Map<String, String>,
    onUpdate: ((AppLimit) -> AppLimit) -> Unit,
    onChangeApps: () -> Unit,
) {
    DraftTextField(
        value = limit.name,
        onCommit = { v -> onUpdate { it.copy(name = v) } },
        label = "Name (optional)",
        placeholder = "Social",
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )

    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Apps", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                limit.packages.sorted().joinToString(", ") { labels[it] ?: it }.ifEmpty { "None" },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TextButton(onClick = onChangeApps) { Text("Change") }
    }

    SettingRow(
        "Only at certain hours",
        supporting = LimitSummary.window(limit)?.let { "In force $it. Outside those hours this limit does nothing." }
            ?: "All day.",
    ) {
        Switch(
            checked = !limit.isAllDay,
            onCheckedChange = { on ->
                onUpdate {
                    if (on) it.copy(windowStartMinute = 14 * 60, windowEndMinute = 17 * 60)
                    else it.copy(windowStartMinute = null, windowEndMinute = null)
                }
            },
        )
    }
    if (!limit.isAllDay) {
        TimeRow("From", limit.windowStartMinute!!) { m -> onUpdate { it.copy(windowStartMinute = m) } }
        TimeRow("Until", limit.windowEndMinute!!) { m -> onUpdate { it.copy(windowEndMinute = m) } }
        Hint("The budget counts only what happens inside these hours. Set the opens to 0 to shut the app for the whole window.")
    }

    Text(
        "Limit by",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
    RadioGroup(
        options = LimitMode.entries,
        selected = limit.limitMode,
        label = {
            when (it) {
                LimitMode.OPENS -> "Opens per day"
                LimitMode.TIME -> "Minutes per day"
            }
        },
        onPick = { mode -> onUpdate { it.copy(limitMode = mode) } },
    )

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when (limit.limitMode) {
            LimitMode.OPENS -> NumberField("Opens / day", limit.dailyOpens, Modifier.weight(1f)) { v ->
                onUpdate { it.copy(dailyOpens = v) }
            }
            LimitMode.TIME -> NumberField("Minutes / day", limit.dailyMinutes, Modifier.weight(1f)) { v ->
                onUpdate { it.copy(dailyMinutes = v) }
            }
        }
        NumberField("Session, min", limit.sessionMinutes, Modifier.weight(1f)) { v ->
            onUpdate { it.copy(sessionMinutes = v) }
        }
    }
    Hint(
        "A session runs from the moment you go in, whether or not you stay. Coming back inside it " +
            "is the same open: no pause, no second open. When it ends, you are asked again with the " +
            "pause, and continuing is a new open. Only the minutes budget counts time in the app.",
        Modifier.padding(bottom = 4.dp),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NumberField("Cooldown, min", limit.cooldownMinutes, Modifier.weight(1f)) { v ->
            onUpdate { it.copy(cooldownMinutes = v) }
        }
        NumberField("Pause, seconds", limit.preOpenDelaySeconds.takeIf { it > 0 }, Modifier.weight(1f)) { v ->
            onUpdate { it.copy(preOpenDelaySeconds = v ?: 0) }
        }
    }
    Hint(
        "Every open is asked about first. With a pause length, that is a countdown; blank, it is a " +
            "plain question with Continue available at once.",
        Modifier.padding(bottom = 4.dp),
    )
}

/** Empty means "no limit", so the value is nullable all the way down. */
@Composable
private fun NumberField(
    label: String,
    value: Int?,
    modifier: Modifier = Modifier,
    onChange: (Int?) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val cleaned = raw.filter { it.isDigit() }.take(4)
            text = cleaned
            onChange(cleaned.toIntOrNull())
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = anchorTextFieldColors(),
        shape = MaterialTheme.shapes.small,
        modifier = modifier.padding(vertical = 6.dp),
    )
}

/** The evening extras: questions on their own, and the sit lockdown. */
@Composable
fun EveningSection(
    settings: AnchorSettings,
    onChange: ((AnchorSettings) -> AnchorSettings) -> Unit,
) {
    SettingsSection(title = "Evening") {
        SettingRow(
            "Ask the evening questions on their own",
            supporting = "Off: the questions appear only when you open a blocked app. On: they also " +
                "appear when the evening window opens and you are in scope (or location is unknown and " +
                "\"ask anyway\" is on), and again once a minute while you use the phone until answered. " +
                "So a spent limit cannot keep the questions from being asked.",
        ) {
            Switch(
                checked = settings.eveningPromptOnItsOwn,
                onCheckedChange = { on -> onChange { it.copy(eveningPromptOnItsOwn = on) } },
            )
        }
        SoftDivider(Modifier.padding(vertical = 4.dp))
        SettingRow(
            "Sit before the evening opens up",
            supporting = "During the evening window, when the evening location rule says you are in scope, " +
                "the phone is held on a guided breath until today's sitting adds up to the minutes below. " +
                "Sits done earlier in the day count. The dialer, messages and the morning allowlist still work.",
        ) {
            Switch(
                checked = settings.eveningSitRequired,
                onCheckedChange = { on -> onChange { it.copy(eveningSitRequired = on) } },
            )
        }
        if (settings.eveningSitRequired) {
            NumberField("Minutes required", settings.eveningSitMinutes, Modifier.fillMaxWidth()) { v ->
                if (v != null && v > 0) onChange { it.copy(eveningSitMinutes = v) }
            }
        }
    }
}
