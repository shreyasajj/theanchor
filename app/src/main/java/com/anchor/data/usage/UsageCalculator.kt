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

    /** What the first open after a voluntary early lock costs. */
    const val EARLY_LOCK_REOPEN_UNITS = 0.5

    private data class Session(val start: Long, val end: Long?)

    /**
     * @param sessionWindowMillis when set (the app's session cap), a return
     *   that begins before the current open's window has elapsed rejoins that
     *   open instead of starting a new one.
     * @param earlyLocksMillis times the user voluntarily locked the app early.
     *   An early lock always ends the open in progress; the next open costs
     *   [EARLY_LOCK_REOPEN_UNITS] instead of one.
     */
    fun summarize(
        events: List<UsageEvent>,
        packageName: String,
        windowStartMillis: Long,
        nowMillis: Long,
        sessionWindowMillis: Long? = null,
        earlyLocksMillis: List<Long> = emptyList(),
    ): AppUsageSummary {
        val sessions = buildSessions(events, packageName)
        if (sessions.isEmpty()) return AppUsageSummary()

        var foregroundMillis = 0L
        var opens = 0
        var openUnits = 0.0
        var openStart: Long? = null
        var previousEnd: Long? = null

        sessions.forEach { session ->
            // Time: clip the session to the window.
            val effectiveStart = maxOf(session.start, windowStartMillis)
            val effectiveEnd = minOf(session.end ?: nowMillis, nowMillis)
            if (effectiveEnd > effectiveStart) {
                foregroundMillis += effectiveEnd - effectiveStart
            }

            // Was the previous open ended on purpose before this session began?
            val lockedSince = previousEnd != null &&
                earlyLocksMillis.any { it >= previousEnd!! && it <= session.start }

            val rejoins = openStart != null && !lockedSince && (
                (previousEnd != null && session.start - previousEnd!! < OPEN_COALESCE_WINDOW_MILLIS) ||
                    (sessionWindowMillis != null && session.start - openStart!! < sessionWindowMillis)
                )

            if (!rejoins) {
                openStart = session.start
                // Opens: only those that actually began inside the window.
                if (session.start >= windowStartMillis) {
                    opens++
                    openUnits += if (lockedSince) EARLY_LOCK_REOPEN_UNITS else 1.0
                }
            }
            previousEnd = session.end
        }

        return AppUsageSummary(
            foregroundMillis = foregroundMillis,
            opens = opens,
            openUnits = openUnits,
            lastForegroundEndAtMillis = sessions.lastOrNull { it.end != null }?.end,
            currentSessionStartAtMillis = sessions.lastOrNull()?.takeIf { it.end == null }?.start,
            lastOpenStartAtMillis = openStart,
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
