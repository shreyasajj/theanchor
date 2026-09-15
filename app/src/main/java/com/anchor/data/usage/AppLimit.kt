package com.anchor.data.usage

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.anchor.domain.TimeWindow

/** Which daily budget a limit enforces. The other one is ignored. */
enum class LimitMode { OPENS, TIME }

/**
 * One limit, shared by a group of apps. A single limited app is a group of
 * one. Every member draws on the same budget: minutes used in one count
 * against the others, switching between members inside a session is the same
 * open, and the pause and blocked screens speak for the group.
 *
 * Every mechanic is independent and optional: null (or 0 for the pause)
 * means "no limit of this kind". Limits apply 24 hours a day and are
 * unrelated to the Evening Anchor window.
 */
@Entity(tableName = "app_limit")
data class AppLimit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Shown on the pause and blocked screens. Empty: use the apps' names. */
    val name: String = "",

    /** The apps sharing this limit. */
    val packages: Set<String> = emptySet(),

    val enabled: Boolean = true,

    /** Whether the daily budget is counted in opens or in minutes. */
    val limitMode: LimitMode = LimitMode.OPENS,

    /** Total foreground minutes allowed per day. Enforced in TIME mode. */
    val dailyMinutes: Int? = null,

    /** Number of launches allowed per day. Enforced in OPENS mode. */
    val dailyOpens: Int? = null,

    /** Minimum gap after closing before the app may be reopened. */
    val cooldownMinutes: Int? = null,

    /**
     * Length of one session, in foreground minutes. Enforced mid-use; also
     * how long an open stays joinable after stepping out.
     */
    val sessionMinutes: Int? = null,

    /** Forced wait before the app opens. 0 disables the pause screen. */
    val preOpenDelaySeconds: Int = 0,

    /**
     * When this limit applies, as minutes since midnight; null for all day.
     * Outside its window a limit does nothing, so an app may sit in several
     * limits with different windows: two opens 14:00-17:00, one 17:00-21:00.
     * The budget counts usage inside the window only.
     */
    val windowStartMinute: Int? = null,
    val windowEndMinute: Int? = null,
) {
    /** A limit for a single app. */
    constructor(
        packageName: String,
        enabled: Boolean = true,
        limitMode: LimitMode = LimitMode.OPENS,
        dailyMinutes: Int? = null,
        dailyOpens: Int? = null,
        cooldownMinutes: Int? = null,
        sessionMinutes: Int? = null,
        preOpenDelaySeconds: Int = 0,
    ) : this(
        id = 0,
        name = "",
        packages = setOf(packageName),
        enabled = enabled,
        limitMode = limitMode,
        dailyMinutes = dailyMinutes,
        dailyOpens = dailyOpens,
        cooldownMinutes = cooldownMinutes,
        sessionMinutes = sessionMinutes,
        preOpenDelaySeconds = preOpenDelaySeconds,
    )

    /** The daily minute budget, or null when the limit is counted in opens. */
    val effectiveDailyMinutes: Int? get() = dailyMinutes.takeIf { limitMode == LimitMode.TIME }

    /** The daily open budget, or null when the limit is counted in minutes. */
    val effectiveDailyOpens: Int? get() = dailyOpens.takeIf { limitMode == LimitMode.OPENS }

    val hasAnyLimit: Boolean
        get() = effectiveDailyMinutes != null || effectiveDailyOpens != null ||
            cooldownMinutes != null || sessionMinutes != null || preOpenDelaySeconds > 0

    /** True for a limit that is enforced at all. */
    val isActive: Boolean get() = enabled && hasAnyLimit

    /**
     * The key every ledger uses for this limit: pauses owed, early locks,
     * streak bypasses. By id, so a group's members share one debt.
     */
    val subject: String get() = "limit:$id"

    operator fun contains(packageName: String): Boolean = packageName in packages

    val isAllDay: Boolean get() = windowStartMinute == null || windowEndMinute == null

    /** Whether this limit is in force at [minuteOfDay]. */
    fun appliesAt(minuteOfDay: Int): Boolean {
        val start = windowStartMinute ?: return true
        val end = windowEndMinute ?: return true
        return TimeWindow.contains(minuteOfDay, start, end)
    }

    /** Whether the two limits are ever in force at the same minute. */
    fun overlapsInTime(other: AppLimit): Boolean {
        if (isAllDay || other.isAllDay) return true
        return TimeWindow.overlaps(windowStartMinute!!, windowEndMinute!!, other.windowStartMinute!!, other.windowEndMinute!!)
    }

    /** A blank slate for the picker: every app it lists must be free at these times. */
    fun clashesWith(other: AppLimit): Boolean =
        other.id != id && other.packages.any { it in packages } && overlapsInTime(other)
}
