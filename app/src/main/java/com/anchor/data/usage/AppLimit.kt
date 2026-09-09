package com.anchor.data.usage

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-app usage limits. Every mechanic is independent and optional: null
 * (or 0 for the pause) means "no limit of this kind".
 *
 * Limits apply 24 hours a day and are unrelated to the Evening Anchor window.
 */
@Entity(tableName = "app_limit")
data class AppLimit(
    @PrimaryKey val packageName: String,
    val enabled: Boolean = true,

    /** Total foreground minutes allowed per day. */
    val dailyMinutes: Int? = null,

    /** Number of launches allowed per day. */
    val dailyOpens: Int? = null,

    /** Minimum gap after closing before the app may be reopened. */
    val cooldownMinutes: Int? = null,

    /** Maximum length of any single session, enforced mid-use. */
    val sessionMinutes: Int? = null,

    /** Forced wait before the app opens. 0 disables the pause screen. */
    val preOpenDelaySeconds: Int = 0,
) {
    val hasAnyLimit: Boolean
        get() = dailyMinutes != null || dailyOpens != null || cooldownMinutes != null ||
            sessionMinutes != null || preOpenDelaySeconds > 0
}
