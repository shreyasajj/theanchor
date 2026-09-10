package com.anchor.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anchor.data.db.DailyLog
import com.anchor.data.db.DailyLogDao
import com.anchor.data.ha.KillSwitch
import com.anchor.data.ha.OverrideStatus
import com.anchor.data.settings.AnchorSettings
import com.anchor.data.settings.SettingsRepository
import com.anchor.data.usage.AppLimitDao
import com.anchor.domain.AnchorDate
import com.anchor.domain.LimitGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

data class UsageRow(
    val packageName: String,
    val label: String,
    val usedMinutes: Int,
    val limitMinutes: Int?,
    val opens: Int,
    val limitOpens: Int?,
    /** Opens weighted for limits (an early-lock return costs half). */
    val openUnits: Double = opens.toDouble(),
) {
    /** Progress through the time budget, or null when there is none. */
    val timeFraction: Float?
        get() = limitMinutes?.let { limit ->
            if (limit <= 0) 1f else (usedMinutes.toFloat() / limit).coerceIn(0f, 1f)
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
)

class DashboardViewModel(
    private val dailyLogDao: DailyLogDao,
    private val anchorDate: AnchorDate,
    private val killSwitch: KillSwitch,
    private val settingsRepository: SettingsRepository,
    private val limitGate: LimitGate,
    private val appLimitDao: AppLimitDao,
    private val appLabels: suspend () -> Map<String, String>,
    private val readPermissions: (exportTreeUri: String?) -> PermissionState,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val settings = settingsRepository.current()
            val today = anchorDate.today()
            val recent = dailyLogDao.recent(limit = 14).associateBy { it.date }

            val labels = appLabels()
            val usage = appLimitDao.all()
                .filter { it.enabled && it.hasAnyLimit }
                .map { limit ->
                    val summary = limitGate.summaryFor(limit.packageName)
                    UsageRow(
                        packageName = limit.packageName,
                        label = labels[limit.packageName] ?: limit.packageName,
                        usedMinutes = (summary.foregroundMillis / 60_000L).toInt(),
                        limitMinutes = limit.dailyMinutes,
                        opens = summary.opens,
                        openUnits = summary.openUnits,
                        limitOpens = limit.dailyOpens,
                    )
                }

            _state.value = DashboardUiState(
                loaded = true,
                today = recent[today],
                week = weekEnding(today, recent),
                overrideStatus = if (settings.killSwitchEnabled) killSwitch.check(settings) else OverrideStatus.INACTIVE,
                permissions = readPermissions(settings.exportTreeUri),
                usage = usage,
                settings = settings,
            )
        }
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
