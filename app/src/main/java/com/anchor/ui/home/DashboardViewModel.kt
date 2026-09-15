package com.anchor.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anchor.data.db.DailyLog
import com.anchor.data.db.DailyLogDao
import com.anchor.data.ha.KillSwitch
import com.anchor.data.ha.OverrideStatus
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.EnforcementMode
import com.anchor.data.settings.SettingsRepository
import com.anchor.data.usage.AppLimit
import com.anchor.data.usage.MeditationSessionDao
import com.anchor.domain.AnchorDate
import com.anchor.domain.EveningSit
import com.anchor.domain.LimitGate
import com.anchor.domain.LimitStatus
import com.anchor.domain.LimitUsage
import com.anchor.domain.Streak
import com.anchor.ui.settings.sections.formatMinute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** One member of a group, in the row's breakdown. */
data class MemberUsage(val label: String, val usedMinutes: Int, val opens: Int)

/** One limit's day, on the dashboard. A group shows as one row. */
data class UsageRow(
    val label: String,
    val usedMinutes: Int,
    val limitMinutes: Int?,
    val opens: Int,
    val limitOpens: Int?,
    /** Opens as charged against the budget. */
    val openUnits: Double = opens.toDouble(),
    /** Waived for the rest of the day by the streak-mode door. */
    val bypassed: Boolean = false,
    /** Where the limit stands right now: in session, spent, cooling down. */
    val status: LimitStatus = LimitStatus.Available,
    val sessionMinutes: Int? = null,
    val cooldownMinutes: Int? = null,
    /** Each app's share; empty for a limit on a single app. */
    val members: List<MemberUsage> = emptyList(),
) {
    /** Progress through the enforced budget, or null when there is none. */
    val fraction: Float?
        get() = limitMinutes?.let { limit ->
            if (limit <= 0) 1f else (usedMinutes.toFloat() / limit).coerceIn(0f, 1f)
        } ?: limitOpens?.let { limit ->
            if (limit <= 0) 1f else (openUnits.toFloat() / limit).coerceIn(0f, 1f)
        }

    val isExhausted: Boolean
        get() = (limitMinutes != null && usedMinutes >= limitMinutes) ||
            (limitOpens != null && openUnits >= limitOpens)
}

/** One column in the seven-day strip. */
data class DayMark(
    val date: String,
    val weekdayInitial: String,
    val isToday: Boolean,
    val morningDone: Boolean,
    val eveningDone: Boolean,
)

data class DashboardUiState(
    val loaded: Boolean = false,
    val today: DailyLog? = null,
    val week: List<DayMark> = emptyList(),
    val overrideStatus: OverrideStatus = OverrideStatus.INACTIVE,
    val permissions: PermissionState? = null,
    val usage: List<UsageRow> = emptyList(),
    val settings: AnchorSettings = AnchorSettings(),
    val meditationSecondsToday: Int = 0,
    val meditationCountToday: Int = 0,
    /** Days within limits; null when streak mode is off. */
    val streakDays: Int? = null,
) {
    val eveningSitDone: Boolean get() = EveningSit.isSatisfied(settings, meditationSecondsToday)
}

class DashboardViewModel(
    private val dailyLogDao: DailyLogDao,
    private val anchorDate: AnchorDate,
    private val killSwitch: KillSwitch,
    private val settingsRepository: SettingsRepository,
    private val limitGate: LimitGate,
    private val meditationDao: MeditationSessionDao,
    private val appLabels: suspend () -> Map<String, String>,
    private val readPermissions: (exportTreeUri: String?) -> PermissionState,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init { refresh() }

    /** Everything, including the Home Assistant round trip and permissions. */
    fun refresh() {
        viewModelScope.launch {
            val today = anchorDate.today()
            val usageDay = anchorDate.usageDay(settingsRepository.current().dayResetMinute)
            settingsRepository.ensureStreakStarted(usageDay)
            val settings = settingsRepository.current()
            val recent = dailyLogDao.recent(limit = 14).associateBy { it.date }
            val live = liveState(settings, usageDay)

            _state.value = DashboardUiState(
                loaded = true,
                today = recent[today],
                week = weekEnding(today, recent),
                overrideStatus = if (settings.killSwitchEnabled) killSwitch.check(settings) else OverrideStatus.INACTIVE,
                permissions = readPermissions(settings.exportTreeUri),
                usage = live.usage,
                settings = settings,
                meditationSecondsToday = live.meditationSeconds,
                meditationCountToday = live.meditationCount,
                streakDays = live.streakDays,
            )
        }
    }

    /**
     * Only what changes while the phone is in use: usage, breathing and the
     * streak. No network, no permission reads, so it is cheap enough to run
     * every few seconds while the dashboard is in front.
     */
    fun tick() {
        viewModelScope.launch {
            if (!_state.value.loaded) return@launch
            val settings = settingsRepository.current()
            val live = liveState(settings, anchorDate.usageDay(settings.dayResetMinute))
            _state.update {
                it.copy(
                    usage = live.usage,
                    meditationSecondsToday = live.meditationSeconds,
                    meditationCountToday = live.meditationCount,
                    streakDays = live.streakDays,
                )
            }
        }
    }

    private class Live(
        val usage: List<UsageRow>,
        val meditationSeconds: Int,
        val meditationCount: Int,
        val streakDays: Int?,
    )

    private suspend fun liveState(settings: AnchorSettings, usageDay: String): Live {
        val dayStart = anchorDate.usageDayStartMillis(settings.dayResetMinute)
        val labels = appLabels()
        return Live(
            usage = limitGate.dashboardUsage().map { row(it, labels, settings, usageDay) },
            meditationSeconds = meditationDao.secondsSince(dayStart),
            meditationCount = meditationDao.countSince(dayStart),
            streakDays = if (settings.enforcementMode == EnforcementMode.STREAK) {
                Streak.count(settings.streakStartDay, usageDay)
            } else null,
        )
    }

    private fun row(usage: LimitUsage, labels: Map<String, String>, settings: AnchorSettings, usageDay: String): UsageRow {
        val limit = usage.limit
        val summary = usage.summary
        return UsageRow(
            label = displayName(limit, labels),
            usedMinutes = (summary.foregroundMillis / 60_000L).toInt(),
            limitMinutes = limit.effectiveDailyMinutes,
            opens = summary.opens,
            openUnits = summary.openUnits,
            limitOpens = limit.effectiveDailyOpens,
            bypassed = Streak.isBypassed(settings.limitBypasses, limit.subject, usageDay),
            status = usage.status,
            sessionMinutes = limit.sessionMinutes,
            cooldownMinutes = limit.cooldownMinutes,
            members = if (limit.packages.size <= 1) emptyList() else usage.perApp.map { (pkg, own) ->
                MemberUsage(
                    label = labels[pkg] ?: pkg,
                    usedMinutes = (own.foregroundMillis / 60_000L).toInt(),
                    opens = own.opens,
                )
            }.sortedWith(compareByDescending<MemberUsage> { it.usedMinutes }.thenBy { it.label.lowercase() }),
        )
    }

    private fun displayName(limit: AppLimit, labels: Map<String, String>): String {
        val base = limit.name.trim().takeIf { it.isNotEmpty() } ?: run {
            val names = limit.packages.map { labels[it] ?: it }.sortedBy { it.lowercase() }
            if (names.size <= 2) names.joinToString(" & ") else "${names[0]}, ${names[1]} +${names.size - 2}"
        }
        val start = limit.windowStartMinute
        val end = limit.windowEndMinute
        return if (start != null && end != null) "$base, ${formatMinute(start)}–${formatMinute(end)}" else base
    }

    private fun weekEnding(today: String, logs: Map<String, DailyLog>): List<DayMark> {
        val end = LocalDate.parse(today)
        return (6 downTo 0).map { back ->
            val date = end.minusDays(back.toLong())
            val iso = anchorDate.format(date)
            val log = logs[iso]
            DayMark(
                date = iso,
                weekdayInitial = date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                isToday = back == 0,
                morningDone = log?.morningCompletedAt != null,
                eveningDone = log?.eveningCompletedAt != null,
            )
        }
    }
}
