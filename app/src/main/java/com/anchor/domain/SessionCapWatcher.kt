package com.anchor.domain

import com.anchor.data.usage.AppLimit
import com.anchor.data.usage.AppUsageSummary
import com.anchor.data.usage.UsageCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SessionCapMath {
    /**
     * Wall-clock time left in the session of the app in front, or of the one
     * a launch now would rejoin. The clock runs from the open's start whether
     * or not the app stays in front; a launch after it ran out is a fresh
     * open with the full length.
     */
    fun remainingMillis(summary: AppUsageSummary, sessionMinutes: Int, nowMillis: Long): Long {
        val window = sessionMinutes * 60_000L
        val start = summary.lastOpenStartAtMillis ?: return window
        val age = nowMillis - start
        return if (UsageCalculator.rejoins(age, window)) window - age else window
    }
}

/** What the watcher needs to know; [LimitGate] answers, tests fake it. */
interface SessionCapQueries {
    suspend fun limitFor(packageName: String): AppLimit?

    /** Milliseconds left in the session, or null when no session cap applies. */
    suspend fun sessionRemainingMillis(packageName: String): Long?

    /** Whether the app, or any app sharing its limit, is in front by the usage log. */
    suspend fun isInForeground(packageName: String): Boolean
}

/**
 * The one limit that cannot be answered by a query, because it must interrupt
 * an app already in use. Arms a timer when a capped app comes to the
 * foreground. Members of one group share the timer: switching between them
 * does not restart it.
 *
 * The timer trusts nothing it was told earlier. Window events arrive for the
 * keyboard, the notification shade and our own floating button, and none of
 * those mean the user left; cancelling on them is what made the cap fire
 * only on the next open. So nothing but a lockdown cancels it. Instead, when
 * it fires, it asks again: if a fresh open began meanwhile it re-arms for
 * that one, and if the app is no longer in front it lets the next launch be
 * handled by the gate.
 */
class SessionCapWatcher(
    private val scope: CoroutineScope,
    private val queries: SessionCapQueries,
    private val anchorDate: AnchorDate,
    private val onCapReached: (packageName: String, nowMillis: Long) -> Unit,
) {
    private val lock = Mutex()
    private var timer: Job? = null
    private var watchedSubject: String? = null

    /** Call on every foreground-app change, including to unlimited apps. */
    fun onForegroundApp(packageName: String) {
        scope.launch {
            val limit = queries.limitFor(packageName) ?: return@launch
            lock.withLock {
                if (limit.subject == watchedSubject && timer?.isActive == true) return@launch
                val remaining = queries.sessionRemainingMillis(packageName) ?: return@launch
                arm(packageName, limit, remaining)
            }
        }
    }

    /** Must be called with [lock] held. */
    private fun arm(packageName: String, limit: AppLimit, remaining: Long) {
        timer?.cancel()
        watchedSubject = limit.subject
        timer = scope.launch {
            delay(remaining)
            fire(packageName, limit)
        }
    }

    private suspend fun fire(packageName: String, limit: AppLimit) {
        val window = limit.sessionMinutes?.let { it * 60_000L } ?: return
        val remaining = queries.sessionRemainingMillis(packageName) ?: return
        if (remaining in 1 until window) {
            // A newer open is running; this timer was for the one before.
            lock.withLock { arm(packageName, limit, remaining) }
            return
        }
        if (!queries.isInForeground(packageName)) return
        onCapReached(packageName, anchorDate.nowMillis())
    }

    fun cancel() {
        scope.launch {
            lock.withLock {
                timer?.cancel()
                timer = null
                watchedSubject = null
            }
        }
    }
}
