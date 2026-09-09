package com.anchor.data.usage

/**
 * Derives usage from a raw event list. Pure by design: the app deliberately
 * keeps no counters, because counters drift, are lost when the process dies,
 * and double-count when the accessibility service restarts mid-session.
 */
object UsageCalculator {

    /**
     * Two sessions closer together than this are one "open". Without it,
     * glancing at the notification shade would burn an open.
     */
    const val OPEN_COALESCE_WINDOW_MILLIS = 60_000L

    private data class Session(val start: Long, val end: Long?)

    fun summarize(
        events: List<UsageEvent>,
        packageName: String,
        windowStartMillis: Long,
        nowMillis: Long,
    ): AppUsageSummary {
        val sessions = buildSessions(events, packageName)
        if (sessions.isEmpty()) return AppUsageSummary()

        var foregroundMillis = 0L
        var opens = 0

        sessions.forEachIndexed { index, session ->
            // Time: clip the session to the window.
            val effectiveStart = maxOf(session.start, windowStartMillis)
            val effectiveEnd = minOf(session.end ?: nowMillis, nowMillis)
            if (effectiveEnd > effectiveStart) {
                foregroundMillis += effectiveEnd - effectiveStart
            }

            // Opens: only sessions that actually began inside the window,
            // and only if they are not a quick re-entry into the previous one.
            if (session.start >= windowStartMillis) {
                val previousEnd = sessions.getOrNull(index - 1)?.end
                val isNewOpen = previousEnd == null ||
                    session.start - previousEnd >= OPEN_COALESCE_WINDOW_MILLIS
                if (isNewOpen) opens++
            }
        }

        return AppUsageSummary(
            foregroundMillis = foregroundMillis,
            opens = opens,
            lastForegroundEndAtMillis = sessions.lastOrNull { it.end != null }?.end,
            currentSessionStartAtMillis = sessions.lastOrNull()?.takeIf { it.end == null }?.start,
        )
    }

    private fun buildSessions(events: List<UsageEvent>, packageName: String): List<Session> {
        val ordered = events
            .filter { it.packageName == packageName }
            .sortedBy { it.timestampMillis }

        val sessions = mutableListOf<Session>()
        var openStart: Long? = null

        ordered.forEach { event ->
            when (event.type) {
                // A second FOREGROUND with no BACKGROUND between is the same
                // session; Android emits these for configuration changes.
                UsageEvent.Type.FOREGROUND ->
                    if (openStart == null) openStart = event.timestampMillis

                UsageEvent.Type.BACKGROUND -> openStart?.let { start ->
                    sessions += Session(start, event.timestampMillis)
                    openStart = null
                }
            }
        }
        openStart?.let { sessions += Session(it, null) }
        return sessions
    }
}
