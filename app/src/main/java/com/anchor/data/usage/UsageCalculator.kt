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

    /** Single-app convenience. */
    fun summarize(
        events: List<UsageEvent>,
        packageName: String,
        windowStartMillis: Long,
        nowMillis: Long,
        sessionWindowMillis: Long? = null,
        earlyLocksMillis: List<Long> = emptyList(),
    ): AppUsageSummary = summarize(
        events, setOf(packageName), windowStartMillis, nowMillis, sessionWindowMillis, earlyLocksMillis,
    )

    /**
     * Whether a return [openAgeMillis] after the open began continues it. A
     * session is wall clock from the open: the app is joinable, unasked, for
     * that long, and afterwards the next entry is a new open, even a second
     * later. Only the daily minute budget counts foreground time.
     */
    fun rejoins(openAgeMillis: Long, sessionWindowMillis: Long): Boolean = openAgeMillis < sessionWindowMillis

    /**
     * @param packageNames the apps sharing one limit; their sessions are
     *   merged and counted as one.
     * @param sessionWindowMillis when set (the limit's session length), a
     *   return inside that long of the open's start continues it instead of
     *   starting a new one; after it, any return is a new open.
     * @param earlyLocksMillis times the user voluntarily locked the app early.
     *   An early lock always ends the open in progress, so the next entry is
     *   a new open however soon it comes.
     */
    fun summarize(
        events: List<UsageEvent>,
        packageNames: Set<String>,
        windowStartMillis: Long,
        nowMillis: Long,
        sessionWindowMillis: Long? = null,
        earlyLocksMillis: List<Long> = emptyList(),
    ): AppUsageSummary {
        val sessions = buildSessions(events, packageNames, nowMillis)
        if (sessions.isEmpty()) return AppUsageSummary()

        var foregroundMillis = 0L
        var opens = 0
        var openUnits = 0.0
        var lastOpenUnits = 0.0
        var openStart: Long? = null
        var openForeground = 0L
        var previousEnd: Long? = null

        sessions.forEach { session ->
            val sessionEnd = minOf(session.end ?: nowMillis, nowMillis)

            // Time: clip the session to the window.
            val effectiveStart = maxOf(session.start, windowStartMillis)
            if (sessionEnd > effectiveStart) {
                foregroundMillis += sessionEnd - effectiveStart
            }

            // Was the previous open ended on purpose before this session began?
            val lockedSince = previousEnd != null &&
                earlyLocksMillis.any { it >= previousEnd!! && it <= session.start }

            val rejoinsOpen = openStart != null && previousEnd != null && !lockedSince && (
                if (sessionWindowMillis != null) rejoins(session.start - openStart!!, sessionWindowMillis)
                else session.start - previousEnd!! < OPEN_COALESCE_WINDOW_MILLIS
                )

            if (!rejoinsOpen) {
                openStart = session.start
                openForeground = 0L
                lastOpenUnits = 0.0
                // Opens: only those that actually began inside the window.
                if (session.start >= windowStartMillis) {
                    opens++
                    lastOpenUnits = 1.0
                    openUnits += lastOpenUnits
                }
            }
            if (sessionEnd > session.start) openForeground += sessionEnd - session.start
            previousEnd = session.end
        }

        val running = sessions.last().end == null
        return AppUsageSummary(
            foregroundMillis = foregroundMillis,
            opens = opens,
            openUnits = openUnits,
            lastOpenUnits = lastOpenUnits,
            lastForegroundEndAtMillis = sessions.lastOrNull { it.end != null }?.end,
            currentSessionStartAtMillis = sessions.last().takeIf { it.end == null }?.start,
            lastOpenStartAtMillis = openStart,
            lastOpenEndAtMillis = if (running) null else sessions.last().end,
            currentOpenForegroundMillis = openForeground,
        )
    }

    /**
     * Sessions of every member app, merged where they touch or overlap: a
     * group's members hand over to each other with the next one's FOREGROUND
     * sometimes logged before the previous one's BACKGROUND.
     */
    private fun buildSessions(events: List<UsageEvent>, packageNames: Set<String>, nowMillis: Long): List<Session> {
        val perPackage = events
            .filter { it.packageName in packageNames }
            .groupBy { it.packageName }
            .values
            .flatMap { buildSessionsFor(it) }
            .sortedBy { it.start }
        if (perPackage.size <= 1) return perPackage

        val merged = mutableListOf<Session>()
        perPackage.forEach { session ->
            val last = merged.lastOrNull()
            if (last != null && session.start < (last.end ?: Long.MAX_VALUE)) {
                val end = if (last.end == null || session.end == null) null else maxOf(last.end, session.end)
                merged[merged.lastIndex] = Session(last.start, end)
            } else {
                merged += session
            }
        }
        return merged
    }

    private fun buildSessionsFor(events: List<UsageEvent>): List<Session> {
        val ordered = events.sortedBy { it.timestampMillis }
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
