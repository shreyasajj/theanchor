package com.anchor.domain

import com.anchor.data.usage.AppLimitDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object SessionCapMath {
    /**
     * How long until the current session hits its cap, or null when the app
     * is not currently in the foreground.
     */
    fun remainingMillis(
        sessionMinutes: Int,
        currentSessionStartAtMillis: Long?,
        nowMillis: Long,
    ): Long? {
        val start = currentSessionStartAtMillis ?: return null
        val elapsed = nowMillis - start
        return maxOf(0L, sessionMinutes * 60_000L - elapsed)
    }
}

/**
 * The one limit that cannot be answered by a query, because it must interrupt
 * an app already in use. Arms a timer when a capped app comes to the
 * foreground and cancels it the moment anything else does.
 */
class SessionCapWatcher(
    private val scope: CoroutineScope,
    private val limitGate: LimitGate,
    private val appLimitDao: AppLimitDao,
    private val anchorDate: AnchorDate,
    private val onCapReached: (packageName: String, nowMillis: Long) -> Unit,
) {
    private var timer: Job? = null
    private var watchedPackage: String? = null

    /** Call on every foreground-app change, including to unlimited apps. */
    fun onForegroundApp(packageName: String) {
        if (packageName == watchedPackage) return   // same app, timer stands
        cancel()

        timer = scope.launch {
            val cap = appLimitDao.find(packageName)
                ?.takeIf { it.enabled }
                ?.sessionMinutes
                ?: return@launch

            val summary = limitGate.summaryFor(packageName)
            val remaining = SessionCapMath.remainingMillis(
                sessionMinutes = cap,
                currentSessionStartAtMillis = summary.currentSessionStartAtMillis
                    ?: anchorDate.nowMillis(),   // event not logged yet; assume now
                nowMillis = anchorDate.nowMillis(),
            ) ?: return@launch

            watchedPackage = packageName
            delay(remaining)
            onCapReached(packageName, anchorDate.nowMillis())
        }
    }

    fun cancel() {
        timer?.cancel()
        timer = null
        watchedPackage = null
    }
}
